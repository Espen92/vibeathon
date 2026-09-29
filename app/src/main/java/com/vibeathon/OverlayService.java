package com.vibeathon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vibeathon.core.RecordingLimits;
import com.vibeathon.core.Selectors;
import com.vibeathon.core.VoiceState;
import com.vibeathon.core.VoiceStateMachine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Foreground service that shows the draggable overlay bubble and runs the tap-tap flow:
 * record → send to ChatGPT → wait for reply → Read aloud.
 */
public final class OverlayService extends Service {

    public static final String ACTION_STOP = "com.vibeathon.action.STOP";
    public static final String ACTION_CANCEL = "com.vibeathon.action.CANCEL";

    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIFICATION_ID = 1;
    private static final long SEND_TIMEOUT_MS = 30_000;
    private static final long HINT_DURATION_MS = 3000;

    private static boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoiceStateMachine machine = new VoiceStateMachine();

    private AppSettings settings;
    private AudioRecorder recorder;
    private Transcriber transcriber;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView bubble;
    private TextView label;
    private WindowManager.LayoutParams params;
    private Uri publishedUri;
    private long recordingStartedAt;
    private String lastNotificationStatus;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        recorder = new AudioRecorder(this);
        transcriber = new Transcriber(this);
        windowManager = getSystemService(WindowManager.class);
        createChannel();
        if (!startInForeground()) {
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.log("Overlay permission missing; stopping overlay");
            stopSelf();
            return;
        }
        try {
            addOverlay();
        } catch (RuntimeException e) {
            DebugLog.error("Could not add overlay", e);
            stopSelf();
            return;
        }
        running = true;
        machine.setListener((from, to, message) -> {
            DebugLog.log("State " + from + " -> " + to + (message == null ? "" : ": " + message));
            render();
        });
        render();
        DebugLog.log("Overlay started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            DebugLog.log("Stop from notification");
            stopSelf();
        } else if (ACTION_CANCEL.equals(action)) {
            DebugLog.log("Cancel from notification");
            cancelCurrent();
            machine.cancel();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        machine.setListener(null);
        cancelCurrent();
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException e) {
                DebugLog.error("removeView failed", e);
            }
            overlay = null;
        }
        DebugLog.log("Overlay stopped");
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------
    // Flow
    // ---------------------------------------------------------------------------------------

    private void onBubbleTapped() {
        try {
            VoiceState before = machine.state();
            VoiceStateMachine.TapAction action = machine.onTap();
            DebugLog.log("Tap in " + before + " -> " + action);
            switch (action) {
                case START_RECORDING:
                    startRecording();
                    break;
                case STOP_AND_SEND:
                    stopAndSend();
                    break;
                case BARGE_IN:
                    ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
                    if (a11y != null) {
                        if (before == VoiceState.AWAITING_REPLY) {
                            a11y.stopGenerating();
                        } else {
                            a11y.stopReading();
                        }
                    }
                    cleanupPublished();
                    startRecording();
                    break;
                case IGNORE:
                default:
                    break;
            }
        } catch (RuntimeException e) {
            fail(getString(R.string.error_automation), e);
        }
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.error_mic_permission), null);
            return;
        }
        int session = machine.session();
        try {
            if (settings.strategy() == AppSettings.Strategy.TRANSCRIBE) {
                if (!Transcriber.isAvailable(this)) {
                    fail(getString(R.string.error_no_recognizer), null);
                    return;
                }
                transcriber.start(new Transcriber.Callback() {
                    @Override
                    public void onTranscript(String text) {
                    }

                    @Override
                    public void onError(String message) {
                        if (machine.isCurrent(session, VoiceState.RECORDING)) {
                            fail(message, null);
                        }
                    }
                });
                handler.postDelayed(() -> {
                    if (machine.isCurrent(session, VoiceState.RECORDING)) {
                        DebugLog.log("Max recording length reached");
                        if (machine.onRecordingStopped()) {
                            stopAndSend();
                        }
                    }
                }, settings.maxRecordingSeconds() * 1000L);
            } else {
                recorder.start(settings.maxRecordingSeconds(), () -> {
                    DebugLog.log("Max recording length reached");
                    if (machine.onRecordingStopped()) {
                        stopAndSend();
                    }
                });
            }
        } catch (Exception e) {
            fail(getString(R.string.error_recording_failed), e);
            return;
        }
        recordingStartedAt = SystemClock.elapsedRealtime();
        startTicker();
    }

    private void stopAndSend() {
        int session = machine.session();
        if (transcriber.isActive()) {
            transcriber.stop(new Transcriber.Callback() {
                @Override
                public void onTranscript(String text) {
                    if (machine.isCurrent(session, VoiceState.SENDING)) {
                        DebugLog.log("Transcript (" + text.length() + " chars)");
                        deliverText(session, text);
                    }
                }

                @Override
                public void onError(String message) {
                    if (machine.isCurrent(session, VoiceState.SENDING)) {
                        fail(message, null);
                    }
                }
            });
            return;
        }
        AudioRecorder.Result result = recorder.stop();
        if (result.tooShort || result.file == null) {
            machine.onRecordingDiscarded(getString(R.string.hint_too_short));
            return;
        }
        if (settings.strategy() == AppSettings.Strategy.ACCESSIBILITY) {
            deliverViaAccessibility(session, result.file);
        } else {
            deliverViaShare(session, result.file);
        }
    }

    private void deliverViaShare(int session, File file) {
        Uri uri = AudioFileProvider.uriFor(this, file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(AudioFileProvider.MIME_TYPE);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, settings.prompt());
        send.setClipData(ClipData.newRawUri(file.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        send.setPackage(Selectors.CHATGPT_PACKAGE);
        try {
            grantUriPermission(Selectors.CHATGPT_PACKAGE, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(send);
            DebugLog.log("Shared " + file.getName() + " to ChatGPT");
        } catch (RuntimeException e) {
            fail(getString(R.string.error_share_failed), e);
            return;
        }
        ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
        if (a11y == null) {
            fail(getString(R.string.error_accessibility_disabled), null);
            return;
        }
        a11y.pressSendWhenReady(settings.prompt(), SEND_TIMEOUT_MS, sentCallback(session));
    }

    private void deliverViaAccessibility(int session, File file) {
        ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
        if (a11y == null) {
            fail(getString(R.string.error_accessibility_disabled), null);
            return;
        }
        String displayName = publishToDownloads(file);
        if (displayName == null) {
            fail(getString(R.string.error_publish_failed), null);
            return;
        }
        a11y.attachFileAndSend(displayName, settings.prompt(), SEND_TIMEOUT_MS,
                sentCallback(session));
    }

    private void deliverText(int session, String text) {
        ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
        if (a11y != null) {
            a11y.typeAndSend(text, SEND_TIMEOUT_MS, sentCallback(session));
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        send.setPackage(Selectors.CHATGPT_PACKAGE);
        try {
            startActivity(send);
        } catch (RuntimeException e) {
            DebugLog.error("Text share failed", e);
        }
        fail(getString(R.string.error_accessibility_disabled), null);
    }

    private ChatGptAccessibilityService.Callback sentCallback(int session) {
        return new ChatGptAccessibilityService.Callback() {
            @Override
            public void onSuccess() {
                if (!machine.isCurrent(session, VoiceState.SENDING) || !machine.onSent()) {
                    return;
                }
                ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
                if (a11y == null) {
                    fail(getString(R.string.error_accessibility_disabled), null);
                    return;
                }
                a11y.awaitReply(settings.replyTimeoutSeconds() * 1000L, replyCallback(session));
            }

            @Override
            public void onFailure(String message) {
                if (machine.isCurrent(session, VoiceState.SENDING)) {
                    fail(message, null);
                }
            }
        };
    }

    private ChatGptAccessibilityService.Callback replyCallback(int session) {
        return new ChatGptAccessibilityService.Callback() {
            @Override
            public void onSuccess() {
                if (!machine.isCurrent(session, VoiceState.AWAITING_REPLY)) {
                    return;
                }
                cleanupPublished();
                boolean autoRead = settings.autoReadAloud();
                machine.onReplyComplete(autoRead);
                ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
                if (!autoRead) {
                    return;
                }
                if (a11y == null) {
                    fail(getString(R.string.error_accessibility_disabled), null);
                    return;
                }
                a11y.readAloud(readCallback(session));
            }

            @Override
            public void onFailure(String message) {
                if (machine.isCurrent(session, VoiceState.AWAITING_REPLY)) {
                    fail(message, null);
                }
            }
        };
    }

    private ChatGptAccessibilityService.Callback readCallback(int session) {
        return new ChatGptAccessibilityService.Callback() {
            @Override
            public void onSuccess() {
                if (!machine.isCurrent(session, VoiceState.READING)) {
                    return;
                }
                ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
                if (a11y == null) {
                    machine.onReadingFinished();
                    return;
                }
                a11y.monitorReading(new ChatGptAccessibilityService.Callback() {
                    @Override
                    public void onSuccess() {
                        if (machine.isCurrent(session, VoiceState.READING)) {
                            machine.onReadingFinished();
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        if (machine.isCurrent(session, VoiceState.READING)) {
                            machine.onReadingFinished();
                        }
                    }
                });
            }

            @Override
            public void onFailure(String message) {
                if (machine.isCurrent(session, VoiceState.READING)) {
                    fail(message, null);
                }
            }
        };
    }

    private void fail(String message, Throwable t) {
        if (t != null) {
            DebugLog.error(message, t);
        } else {
            DebugLog.log("ERROR " + message);
        }
        cancelCurrent();
        machine.onError(message);
    }

    /** Stops recording/transcription and any running automation. Never throws. */
    private void cancelCurrent() {
        try {
            if (recorder != null && recorder.isRecording()) {
                recorder.cancel();
            }
            if (transcriber != null && transcriber.isActive()) {
                transcriber.cancel();
            }
            ChatGptAccessibilityService a11y = ChatGptAccessibilityService.get();
            if (a11y != null) {
                a11y.cancelAll();
            }
            cleanupPublished();
        } catch (RuntimeException e) {
            DebugLog.error("Cleanup failed", e);
        }
    }

    /** Copies the recording to Downloads so the system file picker can show it (Android 10+). */
    private String publishToDownloads(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            DebugLog.log("Accessibility attach strategy needs Android 10+");
            return null;
        }
        cleanupPublished();
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, AudioFileProvider.MIME_TYPE);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Vibeathon");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new java.io.IOException("No output stream");
                }
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            publishedUri = uri;
            DebugLog.log("Published " + file.getName() + " to Downloads/Vibeathon");
            return file.getName();
        } catch (Exception e) {
            DebugLog.error("Publishing recording failed", e);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            return null;
        }
    }

    private void cleanupPublished() {
        if (publishedUri == null) {
            return;
        }
        try {
            getContentResolver().delete(publishedUri, null, null);
        } catch (RuntimeException e) {
            DebugLog.error("Could not delete published recording", e);
        }
        publishedUri = null;
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (machine.state() == VoiceState.RECORDING) {
                render();
                handler.postDelayed(this, 500);
            }
        }
    };

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int size = dp(64);
        overlay.addView(bubble, new LinearLayout.LayoutParams(size, size));

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setMaxWidth(dp(200));
        label.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable labelBg = new GradientDrawable();
        labelBg.setColor(0xCC000000);
        labelBg.setCornerRadius(dp(8));
        label.setBackground(labelBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        overlay.addView(label, lp);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(200);

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                        }
                        if (dragging && overlay != null) {
                            params.x = startX + Math.round(dx);
                            params.y = startY + Math.round(dy);
                            windowManager.updateViewLayout(overlay, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            v.performClick();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        bubble.setOnClickListener(v -> onBubbleTapped());
        windowManager.addView(overlay, params);
    }

    private void render() {
        if (bubble == null) {
            return;
        }
        VoiceState state = machine.state();
        String message = machine.message();
        int color;
        String icon;
        String status;
        switch (state) {
            case RECORDING:
                color = 0xFFD32F2F;
                icon = "● " + RecordingLimits.formatElapsed(
                        SystemClock.elapsedRealtime() - recordingStartedAt);
                status = getString(R.string.state_recording);
                break;
            case SENDING:
                color = 0xFFF57C00;
                icon = "⇪";
                status = getString(R.string.state_sending);
                break;
            case AWAITING_REPLY:
                color = 0xFF1976D2;
                icon = "…";
                status = getString(R.string.state_waiting);
                break;
            case READING:
                color = 0xFF388E3C;
                icon = "🔊";
                status = getString(R.string.state_reading);
                break;
            case ERROR:
                color = 0xFF616161;
                icon = "!";
                status = getString(R.string.state_error,
                        message == null ? getString(R.string.error_automation) : message);
                break;
            case IDLE:
            default:
                color = 0xFF212121;
                icon = "🎤";
                status = getString(R.string.state_idle);
                break;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);
        bubble.setText(icon);
        bubble.setContentDescription(status);

        String labelText = status;
        if (state == VoiceState.IDLE && message != null) {
            labelText = message;
            // Clear the hint after a few seconds by re-rendering without it.
            handler.postDelayed(() -> {
                if (machine.state() == VoiceState.IDLE && label != null) {
                    label.setText(getString(R.string.state_idle));
                }
            }, HINT_DURATION_MS);
        }
        label.setText(labelText);
        updateNotification(status);
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), flags);
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_CANCEL), flags);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_stop), stop).build())
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_cancel), cancel).build())
                .build();
    }

    private boolean startInForeground() {
        Notification notification = buildNotification(getString(R.string.state_idle));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            DebugLog.error("startForeground failed (microphone permission granted?)", e);
            return false;
        }
    }

    private void updateNotification(String status) {
        if (status.equals(lastNotificationStatus)) {
            return;
        }
        lastNotificationStatus = status;
        try {
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification(status));
        } catch (RuntimeException e) {
            DebugLog.error("Notification update failed", e);
        }
    }
}
