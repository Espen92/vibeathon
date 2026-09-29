package com.vibeathon;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.vibeathon.core.DeliveryFailureMode;
import com.vibeathon.core.DeliveryStrategy;
import com.vibeathon.core.Selectors;

/** Setup checklist, settings and debug log. */
public class MainActivity extends Activity {

    private static final int REQUEST_MIC = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final long REFRESH_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppSettings settings;

    private TextView statusOverlay;
    private TextView statusAccessibility;
    private TextView statusMicrophone;
    private TextView statusNotifications;
    private TextView statusChatGpt;
    private Button toggleOverlay;
    private TextView debugLog;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final DebugLog.Listener logListener = this::refreshLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);

        statusOverlay = findViewById(R.id.status_overlay);
        statusAccessibility = findViewById(R.id.status_accessibility);
        statusMicrophone = findViewById(R.id.status_microphone);
        statusNotifications = findViewById(R.id.status_notifications);
        statusChatGpt = findViewById(R.id.status_chatgpt);
        toggleOverlay = findViewById(R.id.button_toggle_overlay);
        debugLog = findViewById(R.id.debug_log);

        findViewById(R.id.button_overlay).setOnClickListener(v -> open(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.button_accessibility).setOnClickListener(v -> open(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.button_microphone).setOnClickListener(v -> requestMicrophone());
        findViewById(R.id.button_notifications).setOnClickListener(v -> requestNotifications());
        findViewById(R.id.button_chatgpt).setOnClickListener(v -> openChatGptOrStore());
        findViewById(R.id.button_app_info).setOnClickListener(v -> openAppInfo());
        int restrictedVisibility =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? View.VISIBLE : View.GONE;
        findViewById(R.id.restricted_hint).setVisibility(restrictedVisibility);
        findViewById(R.id.button_app_info).setVisibility(restrictedVisibility);
        toggleOverlay.setOnClickListener(v -> toggleOverlay());

        setUpSettings();

        findViewById(R.id.debug_copy).setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Vibeathon debug log", DebugLog.text()));
            Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.debug_clear).setOnClickListener(v -> DebugLog.clear());
        findViewById(R.id.button_probe).setOnClickListener(v -> {
            Toast.makeText(this, R.string.probe_started, Toast.LENGTH_SHORT).show();
            ShareTargets.probeAndLogAll(this);
        });
        findViewById(R.id.button_retry).setOnClickListener(v -> retryDelivery());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DebugLog.addListener(logListener);
        refreshLog();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DebugLog.removeListener(logListener);
        handler.removeCallbacks(refresher);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted && permissions.length > 0
                && !shouldShowRequestPermissionRationale(permissions[0])) {
            // Permanently denied: the user has to enable it in App info.
            openAppInfo();
        }
        refreshStatus();
    }

    private void setUpSettings() {
        RadioGroup group = findViewById(R.id.strategy_group);
        switch (settings.strategy()) {
            case ACCESSIBILITY:
                group.check(R.id.strategy_accessibility);
                break;
            case TRANSCRIBE:
                group.check(R.id.strategy_transcribe);
                break;
            case SHARE:
            default:
                group.check(R.id.strategy_share);
                break;
        }
        group.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.strategy_accessibility) {
                settings.setStrategy(DeliveryStrategy.ACCESSIBILITY);
            } else if (id == R.id.strategy_transcribe) {
                settings.setStrategy(DeliveryStrategy.TRANSCRIBE);
                if (!Transcriber.isAvailable(this)) {
                    Toast.makeText(this, R.string.transcribe_unavailable, Toast.LENGTH_LONG)
                            .show();
                }
            } else {
                settings.setStrategy(DeliveryStrategy.SHARE);
            }
        });

        RadioGroup failureGroup = findViewById(R.id.failure_group);
        switch (settings.failureMode()) {
            case CHOOSER_ONLY:
                failureGroup.check(R.id.failure_chooser);
                break;
            case FAIL_FAST:
                failureGroup.check(R.id.failure_fail_fast);
                break;
            case FALLBACK_CHAIN:
            default:
                failureGroup.check(R.id.failure_chain);
                break;
        }
        failureGroup.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.failure_chooser) {
                settings.setFailureMode(DeliveryFailureMode.CHOOSER_ONLY);
            } else if (id == R.id.failure_fail_fast) {
                settings.setFailureMode(DeliveryFailureMode.FAIL_FAST);
            } else {
                settings.setFailureMode(DeliveryFailureMode.FALLBACK_CHAIN);
            }
        });

        EditText prompt = findViewById(R.id.prompt);
        prompt.setText(settings.prompt());
        prompt.addTextChangedListener(new SimpleWatcher(s -> settings.setPrompt(s)));

        Switch autoRead = findViewById(R.id.auto_read);
        autoRead.setChecked(settings.autoReadAloud());
        autoRead.setOnCheckedChangeListener((b, checked) -> settings.setAutoReadAloud(checked));

        EditText timeout = findViewById(R.id.reply_timeout);
        timeout.setText(String.valueOf(settings.replyTimeoutSeconds()));
        timeout.addTextChangedListener(new SimpleWatcher(s -> {
            Integer value = parse(s);
            if (value != null) {
                settings.setReplyTimeoutSeconds(value);
            }
        }));
        timeout.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                timeout.setText(String.valueOf(settings.replyTimeoutSeconds()));
            }
        });

        EditText maxRecording = findViewById(R.id.max_recording);
        maxRecording.setText(String.valueOf(settings.maxRecordingSeconds()));
        maxRecording.addTextChangedListener(new SimpleWatcher(s -> {
            Integer value = parse(s);
            if (value != null) {
                settings.setMaxRecordingSeconds(value);
            }
        }));
        maxRecording.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                maxRecording.setText(String.valueOf(settings.maxRecordingSeconds()));
            }
        });
    }

    private void refreshStatus() {
        setStatus(statusOverlay, R.string.check_overlay, Settings.canDrawOverlays(this));
        setStatus(statusAccessibility, R.string.check_accessibility,
                ChatGptAccessibilityService.isEnabled(this));
        setStatus(statusMicrophone, R.string.check_microphone, hasMicrophone());
        setStatus(statusNotifications, R.string.check_notifications, hasNotifications());
        setStatus(statusChatGpt, R.string.check_chatgpt, isChatGptInstalled());
        toggleOverlay.setText(OverlayService.isRunning()
                ? R.string.stop_overlay : R.string.start_overlay);
    }

    private void setStatus(TextView view, int labelRes, boolean ok) {
        view.setText(getString(ok ? R.string.status_ok : R.string.status_missing,
                getString(labelRes)));
    }

    private void refreshLog() {
        String text = DebugLog.text();
        debugLog.setText(text.isEmpty() ? getString(R.string.debug_empty) : text);
    }

    private boolean hasMicrophone() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private boolean isChatGptInstalled() {
        try {
            getPackageManager().getPackageInfo(Selectors.CHATGPT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void requestMicrophone() {
        if (hasMicrophone()) {
            openAppInfo();
        } else {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void openChatGptOrStore() {
        if (isChatGptInstalled() && ChatGptAccessibilityService.launchChatGpt(this)) {
            return;
        }
        Intent market = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + Selectors.CHATGPT_PACKAGE));
        try {
            startActivity(market);
        } catch (ActivityNotFoundException e) {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id="
                            + Selectors.CHATGPT_PACKAGE)));
        }
    }

    private void openAppInfo() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void toggleOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        if (OverlayService.isRunning()) {
            stopService(intent);
        } else if (!Settings.canDrawOverlays(this) || !hasMicrophone()) {
            Toast.makeText(this, R.string.overlay_needs_permissions, Toast.LENGTH_LONG).show();
        } else {
            try {
                startForegroundService(intent);
            } catch (RuntimeException e) {
                DebugLog.error("Could not start overlay", e);
            }
        }
        handler.postDelayed(this::refreshStatus, 300);
    }

    /** Re-sends the last recording that could not be delivered. */
    private void retryDelivery() {
        if (!OverlayService.isRunning()) {
            Toast.makeText(this, R.string.retry_needs_overlay, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_RETRY));
        } catch (RuntimeException e) {
            DebugLog.error("Could not retry delivery", e);
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            DebugLog.error("No activity for " + intent.getAction(), e);
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                // Nothing else we can open.
            }
        }
    }

    private static Integer parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface TextChanged {
        void onChanged(String text);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final TextChanged callback;

        SimpleWatcher(TextChanged callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            callback.onChanged(s.toString());
        }
    }
}
