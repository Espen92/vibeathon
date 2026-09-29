package com.vibeathon;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.vibeathon.core.ReplyCompletionDetector;
import com.vibeathon.core.Selectors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the ChatGPT app UI: presses Send, waits for the reply to finish and taps "Read aloud".
 * All nodes are located by content description / text via {@link Selectors}; coordinates are
 * only used for a last-resort long-press gesture. Every step is written to {@link DebugLog}.
 *
 * <p>All public methods must be called on the main thread. Only one automation task runs at a
 * time; starting a new one or calling {@link #cancelAll()} invalidates older callbacks.
 */
public final class ChatGptAccessibilityService extends AccessibilityService {

    public interface Callback {
        void onSuccess();

        void onFailure(String message);
    }

    private interface Step {
        /** Returns true when the step is done. */
        boolean run();
    }

    private static final long POLL_MS = 250;
    private static final long SEND_STABLE_MS = 800;
    private static final long READING_DETECT_MS = 6000;
    private static final long READING_MAX_MS = 20 * 60 * 1000;
    private static final int MAX_NODES = 6000;
    private static final int TEXT_TAIL_CHARS = 2000;

    private static ChatGptAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int token;
    private int baselineActionCount;
    private String baselineText;

    public static ChatGptAccessibilityService get() {
        return instance;
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName me = new ComponentName(context, ChatGptAccessibilityService.class);
        for (String entry : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (me.equals(cn)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        DebugLog.log("Accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancelAll();
        instance = null;
        DebugLog.log("Accessibility service disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cancelAll();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && token > 0) {
            DebugLog.log("ChatGPT window: " + event.getClassName());
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------------------------------------------------------------------------------
    // Public automation API
    // ---------------------------------------------------------------------------------------

    /** Cancels any running automation. Pending callbacks are dropped. */
    public void cancelAll() {
        token++;
        handler.removeCallbacksAndMessages(null);
    }

    /** Opens ChatGPT (resuming its current chat when it is already running). */
    public boolean bringChatGptToFront() {
        return launchChatGpt(this);
    }

    public static boolean launchChatGpt(Context context) {
        Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage(Selectors.CHATGPT_PACKAGE);
        if (launch == null) {
            DebugLog.log("ChatGPT launch intent not found");
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(launch);
            DebugLog.log("Brought ChatGPT to front");
            return true;
        } catch (RuntimeException e) {
            DebugLog.error("Could not launch ChatGPT", e);
            return false;
        }
    }

    /**
     * Waits until the composer's Send button is enabled (i.e. the attachment finished loading),
     * optionally fills in {@code ensureText} if the composer is empty, then presses Send.
     */
    public void pressSendWhenReady(String ensureText, long timeoutMs, Callback callback) {
        int t = newTask();
        pressSend(t, ensureText, timeoutMs, callback);
    }

    /** Brings ChatGPT to the front, types {@code text} into the composer and presses Send. */
    public void typeAndSend(String text, long timeoutMs, Callback callback) {
        int t = newTask();
        bringChatGptToFront();
        DebugLog.log("Step: waiting for composer to type text");
        poll(t, 10_000, "Composer text field not found", () -> {
            AccessibilityNodeInfo editable = findComposer(chatRoots());
            return editable != null && setText(editable, text);
        }, done(t, () -> pressSend(t, null, timeoutMs, callback), callback));
    }

    /**
     * Strategy B: attach {@code fileDisplayName} through ChatGPT's attachment menu and the
     * system file picker, type {@code prompt} and press Send.
     */
    public void attachFileAndSend(String fileDisplayName, String prompt, long timeoutMs,
            Callback callback) {
        int t = newTask();
        if (!bringChatGptToFront()) {
            callback.onFailure(getString(R.string.error_chatgpt_missing));
            return;
        }
        DebugLog.log("Step: open attachment menu");
        poll(t, 10_000, "Attach button not found", () -> clickFirst(chatRoots(), Selectors.ATTACH),
                done(t, () -> {
                    DebugLog.log("Step: choose Files in attachment menu");
                    poll(t, 6_000, "Files option not found in attachment menu",
                            () -> clickFirst(chatRoots(), Selectors.ATTACH_FILE),
                            done(t, () -> pickFile(t, fileDisplayName, prompt, timeoutMs,
                                    callback), callback));
                }, callback));
    }

    /** Waits until ChatGPT finishes its reply (see {@link ReplyCompletionDetector}). */
    public void awaitReply(long timeoutMs, Callback callback) {
        int t = newTask();
        ReplyCompletionDetector detector = new ReplyCompletionDetector(
                SystemClock::elapsedRealtime, ReplyCompletionDetector.DEFAULT_STABLE_MILLIS,
                timeoutMs);
        detector.start(baselineActionCount, baselineText);
        DebugLog.log("Step: waiting for reply (baseline actions=" + baselineActionCount
                + ", timeout=" + timeoutMs / 1000 + " s)");
        final boolean[] lastStop = {false};
        final String[] lastText = {baselineText};
        final int[] lastCount = {baselineActionCount};
        final ReplyCompletionDetector.Status[] lastStatus = {null};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (t != token) {
                    return;
                }
                try {
                    List<AccessibilityNodeInfo> roots = chatRoots();
                    if (!roots.isEmpty()) {
                        lastStop[0] = !findAll(roots, Selectors.STOP_GENERATING).isEmpty();
                        lastText[0] = conversationTail(roots);
                        lastCount[0] = findAll(roots, Selectors.RESPONSE_ACTIONS).size();
                    }
                    ReplyCompletionDetector.Status status =
                            detector.update(lastStop[0], lastText[0], lastCount[0]);
                    if (status != lastStatus[0]) {
                        DebugLog.log("Reply status: " + status + " (stop=" + lastStop[0]
                                + ", actions=" + lastCount[0] + ")");
                        lastStatus[0] = status;
                    }
                    if (status == ReplyCompletionDetector.Status.COMPLETE) {
                        callback.onSuccess();
                    } else if (status == ReplyCompletionDetector.Status.TIMED_OUT) {
                        callback.onFailure(getString(R.string.error_reply_timeout));
                    } else {
                        handler.postDelayed(this, POLL_MS);
                    }
                } catch (RuntimeException e) {
                    DebugLog.error("awaitReply step failed", e);
                    callback.onFailure(getString(R.string.error_automation));
                }
            }
        };
        handler.post(tick);
    }

    /**
     * Presses "Read aloud" on the latest assistant message: directly if visible, otherwise
     * after scrolling to the bottom, via the "More" menu, or via a long-press.
     */
    public void readAloud(Callback callback) {
        int t = newTask();
        DebugLog.log("Step: read aloud - direct");
        if (clickBottomMost(chatRoots(), Selectors.READ_ALOUD)) {
            callback.onSuccess();
            return;
        }
        DebugLog.log("Step: read aloud - scroll to bottom");
        scrollToBottom(chatRoots());
        later(t, 700, () -> {
            if (clickBottomMost(chatRoots(), Selectors.READ_ALOUD)) {
                callback.onSuccess();
                return;
            }
            DebugLog.log("Step: read aloud - open More actions menu");
            if (clickBottomMost(chatRoots(), Selectors.MORE_ACTIONS)) {
                later(t, 800, () -> {
                    if (clickBottomMost(chatRoots(), Selectors.READ_ALOUD)) {
                        callback.onSuccess();
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        later(t, 500, () -> readAloudViaLongPress(t, callback));
                    }
                });
            } else {
                readAloudViaLongPress(t, callback);
            }
        });
    }

    /**
     * Calls back with success once read-aloud playback appears to have ended (its stop
     * control disappeared) or if no playback control could be detected.
     */
    public void monitorReading(Callback callback) {
        int t = newTask();
        long start = SystemClock.elapsedRealtime();
        final boolean[] seen = {false};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (t != token) {
                    return;
                }
                long elapsed = SystemClock.elapsedRealtime() - start;
                boolean playing;
                try {
                    playing = !findAll(chatRoots(), Selectors.STOP_READING).isEmpty();
                } catch (RuntimeException e) {
                    DebugLog.error("monitorReading step failed", e);
                    callback.onSuccess();
                    return;
                }
                if (playing && !seen[0]) {
                    DebugLog.log("Read aloud playing");
                    seen[0] = true;
                }
                if (seen[0] && !playing) {
                    DebugLog.log("Read aloud finished");
                    callback.onSuccess();
                } else if (!seen[0] && elapsed > READING_DETECT_MS) {
                    DebugLog.log("No playback control detected; assuming read aloud started");
                    callback.onSuccess();
                } else if (elapsed > READING_MAX_MS) {
                    callback.onSuccess();
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        };
        handler.postDelayed(tick, 500);
    }

    /** Best effort: stop read-aloud playback (used for barge-in). */
    public void stopReading() {
        cancelAll();
        if (clickBottomMost(chatRoots(), Selectors.STOP_READING)) {
            DebugLog.log("Stopped read aloud");
        }
    }

    /** Best effort: stop reply generation (used for barge-in). */
    public void stopGenerating() {
        cancelAll();
        if (clickBottomMost(chatRoots(), Selectors.STOP_GENERATING)) {
            DebugLog.log("Stopped reply generation");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Steps
    // ---------------------------------------------------------------------------------------

    private void pressSend(int t, String ensureText, long timeoutMs, Callback callback) {
        DebugLog.log("Step: waiting for enabled Send button");
        final long[] enabledSince = {0};
        final boolean[] typed = {false};
        poll(t, timeoutMs, getString(R.string.error_send_not_found), () -> {
            List<AccessibilityNodeInfo> roots = chatRoots();
            if (roots.isEmpty()) {
                return false;
            }
            if (ensureText != null && !typed[0]) {
                AccessibilityNodeInfo composer = findComposer(roots);
                if (composer != null && (composer.isShowingHintText()
                        || TextUtils.isEmpty(composer.getText()))) {
                    DebugLog.log("Composer empty; typing prompt");
                    setText(composer, ensureText);
                }
                typed[0] = composer != null;
            }
            AccessibilityNodeInfo send = bottomMost(findAll(roots, Selectors.SEND));
            if (send == null || !isEnabledOrClickableAncestor(send)) {
                enabledSince[0] = 0;
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            if (enabledSince[0] == 0) {
                enabledSince[0] = now;
                DebugLog.log("Send button enabled: " + describe(send));
                return false;
            }
            if (now - enabledSince[0] < SEND_STABLE_MS) {
                return false;
            }
            baselineActionCount = findAll(roots, Selectors.RESPONSE_ACTIONS).size();
            baselineText = conversationTail(roots);
            boolean clicked = click(send);
            DebugLog.log("Pressed Send: " + clicked);
            return clicked;
        }, callback);
    }

    private void pickFile(int t, String displayName, String prompt, long timeoutMs,
            Callback callback) {
        DebugLog.log("Step: pick " + displayName + " in file picker");
        poll(t, 15_000, getString(R.string.error_file_not_in_picker), () -> {
            List<AccessibilityNodeInfo> roots = allRoots();
            AccessibilityNodeInfo match = null;
            for (AccessibilityNodeInfo node : traverse(roots)) {
                if (labelEquals(node, displayName)) {
                    match = node;
                    break;
                }
            }
            return match != null && click(match);
        }, done(t, () -> {
            DebugLog.log("Step: type prompt");
            poll(t, 10_000, "Composer text field not found", () -> {
                AccessibilityNodeInfo composer = findComposer(chatRoots());
                return composer != null && setText(composer, prompt);
            }, done(t, () -> pressSend(t, null, timeoutMs, callback), callback));
        }, callback));
    }

    private void readAloudViaLongPress(int t, Callback callback) {
        AccessibilityNodeInfo message = latestMessageNode(chatRoots());
        if (message == null) {
            callback.onFailure(getString(R.string.error_read_aloud_not_found));
            return;
        }
        DebugLog.log("Step: read aloud - long-press latest message " + describe(message));
        AccessibilityNodeInfo target = message;
        while (target != null && !target.isLongClickable()) {
            target = target.getParent();
        }
        boolean pressed = target != null
                && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        if (!pressed) {
            DebugLog.log("Long-click action unavailable; dispatching gesture (last resort)");
            pressed = longPressGesture(message);
        }
        if (!pressed) {
            callback.onFailure(getString(R.string.error_read_aloud_not_found));
            return;
        }
        later(t, 900, () -> {
            if (clickBottomMost(chatRoots(), Selectors.READ_ALOUD)) {
                callback.onSuccess();
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
                callback.onFailure(getString(R.string.error_read_aloud_not_found));
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Task helpers
    // ---------------------------------------------------------------------------------------

    private int newTask() {
        cancelAll();
        return token;
    }

    private void later(int t, long delayMs, Runnable r) {
        handler.postDelayed(() -> {
            if (t == token) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    DebugLog.error("Automation step crashed", e);
                }
            }
        }, delayMs);
    }

    /** Wraps {@code next} so it only runs for the current task; failures go to {@code cb}. */
    private Callback done(int t, Runnable next, Callback cb) {
        return new Callback() {
            @Override
            public void onSuccess() {
                if (t == token) {
                    next.run();
                }
            }

            @Override
            public void onFailure(String message) {
                cb.onFailure(message);
            }
        };
    }

    /** Runs {@code step} every {@link #POLL_MS} until it returns true or the timeout expires. */
    private void poll(int t, long timeoutMs, String timeoutMessage, Step step, Callback cb) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (t != token) {
                    return;
                }
                boolean finished;
                try {
                    finished = step.run();
                } catch (RuntimeException e) {
                    DebugLog.error("Automation step crashed", e);
                    cb.onFailure(getString(R.string.error_automation));
                    return;
                }
                if (finished) {
                    cb.onSuccess();
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    DebugLog.log("Timed out: " + timeoutMessage);
                    logVisibleLabels();
                    cb.onFailure(timeoutMessage);
                } else {
                    handler.postDelayed(this, POLL_MS);
                }
            }
        };
        handler.post(tick);
    }

    // ---------------------------------------------------------------------------------------
    // Node helpers
    // ---------------------------------------------------------------------------------------

    private List<AccessibilityNodeInfo> allRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && !getPackageName().contentEquals(
                        root.getPackageName() == null ? "" : root.getPackageName())) {
                    roots.add(root);
                }
            }
        } catch (RuntimeException e) {
            DebugLog.error("getWindows failed", e);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) {
                roots.add(active);
            }
        }
        return roots;
    }

    private List<AccessibilityNodeInfo> chatRoots() {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        for (AccessibilityNodeInfo root : allRoots()) {
            if (root.getPackageName() != null
                    && Selectors.CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
                result.add(root);
            }
        }
        return result;
    }

    private static List<AccessibilityNodeInfo> traverse(List<AccessibilityNodeInfo> roots) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(roots.get(i));
        }
        while (!stack.isEmpty() && out.size() < MAX_NODES) {
            AccessibilityNodeInfo node = stack.pop();
            out.add(node);
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return out;
    }

    private static List<AccessibilityNodeInfo> findAll(List<AccessibilityNodeInfo> roots,
            List<String> selector) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo node : traverse(roots)) {
            if (node.isVisibleToUser()
                    && Selectors.matchesAny(selector, node.getContentDescription(),
                    node.getText())) {
                out.add(node);
            }
        }
        return out;
    }

    private static AccessibilityNodeInfo bottomMost(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestBottom = Integer.MIN_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo node : nodes) {
            node.getBoundsInScreen(r);
            if (r.bottom >= bestBottom) {
                bestBottom = r.bottom;
                best = node;
            }
        }
        return best;
    }

    private boolean clickFirst(List<AccessibilityNodeInfo> roots, List<String> selector) {
        List<AccessibilityNodeInfo> nodes = findAll(roots, selector);
        if (nodes.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo node = nodes.get(0);
        boolean clicked = click(node);
        DebugLog.log("Click " + describe(node) + ": " + clicked);
        return clicked;
    }

    private boolean clickBottomMost(List<AccessibilityNodeInfo> roots, List<String> selector) {
        AccessibilityNodeInfo node = bottomMost(findAll(roots, selector));
        if (node == null) {
            return false;
        }
        boolean clicked = click(node);
        DebugLog.log("Click " + describe(node) + ": " + clicked);
        return clicked;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target == null) {
            target = node;
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean isEnabledOrClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        return node.isEnabled() && (target == null || target.isEnabled());
    }

    private static AccessibilityNodeInfo findComposer(List<AccessibilityNodeInfo> roots) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo node : traverse(roots)) {
            if (node.isEditable() && node.isVisibleToUser()) {
                if (node.isFocused()) {
                    return node;
                }
                if (fallback == null) {
                    fallback = node;
                }
            }
        }
        return fallback;
    }

    private static boolean setText(AccessibilityNodeInfo editable, String text) {
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        DebugLog.log("Set composer text: " + ok);
        return ok;
    }

    private static boolean labelEquals(AccessibilityNodeInfo node, String label) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        return (text != null && label.contentEquals(text.toString().trim()))
                || (desc != null && label.contentEquals(desc.toString().trim()));
    }

    private static boolean isSelectorLabel(AccessibilityNodeInfo node) {
        CharSequence d = node.getContentDescription();
        CharSequence t = node.getText();
        return Selectors.matchesAny(Selectors.SEND, d, t)
                || Selectors.matchesAny(Selectors.STOP_GENERATING, d, t)
                || Selectors.matchesAny(Selectors.READ_ALOUD, d, t)
                || Selectors.matchesAny(Selectors.STOP_READING, d, t)
                || Selectors.matchesAny(Selectors.RESPONSE_ACTIONS, d, t)
                || Selectors.matchesAny(Selectors.MORE_ACTIONS, d, t)
                || Selectors.matchesAny(Selectors.ATTACH, d, t)
                || Selectors.matchesAny(Selectors.SCROLL_TO_BOTTOM, d, t);
    }

    /**
     * Visible conversation text (non-editable, non-control nodes), trimmed to the last
     * {@link #TEXT_TAIL_CHARS} characters. The tail is dominated by the newest assistant message,
     * so it stops changing when streaming stops.
     */
    private static String conversationTail(List<AccessibilityNodeInfo> roots) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo node : traverse(roots)) {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0 && !node.isEditable()
                    && node.isVisibleToUser() && !isSelectorLabel(node)) {
                sb.append(text).append('\n');
            }
        }
        int start = Math.max(0, sb.length() - TEXT_TAIL_CHARS);
        return sb.substring(start);
    }

    /** Bottom-most visible text node above the composer - the newest assistant message. */
    private static AccessibilityNodeInfo latestMessageNode(List<AccessibilityNodeInfo> roots) {
        AccessibilityNodeInfo composer = findComposer(roots);
        Rect composerBounds = new Rect();
        if (composer != null) {
            composer.getBoundsInScreen(composerBounds);
        }
        AccessibilityNodeInfo best = null;
        int bestBottom = Integer.MIN_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo node : traverse(roots)) {
            CharSequence text = node.getText();
            if (text == null || text.toString().trim().isEmpty() || node.isEditable()
                    || !node.isVisibleToUser() || isSelectorLabel(node)) {
                continue;
            }
            node.getBoundsInScreen(r);
            if (composer != null && r.bottom > composerBounds.top) {
                continue;
            }
            if (r.bottom >= bestBottom) {
                bestBottom = r.bottom;
                best = node;
            }
        }
        return best;
    }

    private void scrollToBottom(List<AccessibilityNodeInfo> roots) {
        if (clickBottomMost(roots, Selectors.SCROLL_TO_BOTTOM)) {
            return;
        }
        AccessibilityNodeInfo scrollable = null;
        int bestArea = 0;
        Rect r = new Rect();
        for (AccessibilityNodeInfo node : traverse(roots)) {
            if (node.isScrollable()) {
                node.getBoundsInScreen(r);
                int area = r.width() * r.height();
                if (area > bestArea) {
                    bestArea = area;
                    scrollable = node;
                }
            }
        }
        if (scrollable != null) {
            for (int i = 0; i < 5; i++) {
                if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    break;
                }
            }
            DebugLog.log("Scrolled conversation forward");
        }
    }

    /** Last resort: long-press gesture at the centre of {@code node}. */
    private boolean longPressGesture(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) {
            return false;
        }
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 800))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static String describe(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        String label = desc != null ? desc.toString() : text != null ? text.toString() : "";
        if (label.length() > 40) {
            label = label.substring(0, 40) + "…";
        }
        return "[" + node.getClassName() + " \"" + label + "\"]";
    }

    /** Dumps short clickable labels to the debug log to help tune {@link Selectors}. */
    private void logVisibleLabels() {
        StringBuilder sb = new StringBuilder("Visible controls:");
        int count = 0;
        for (AccessibilityNodeInfo node : traverse(allRoots())) {
            if (!node.isVisibleToUser() || !(node.isClickable() || node.isLongClickable())) {
                continue;
            }
            CharSequence label = node.getContentDescription() != null
                    ? node.getContentDescription() : node.getText();
            if (label == null || label.length() == 0 || label.length() > 40) {
                continue;
            }
            sb.append(" \"").append(label).append('"');
            if (++count >= 30) {
                break;
            }
        }
        DebugLog.log(sb.toString());
    }
}
