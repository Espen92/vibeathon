package com.vibeathon;

import android.content.Context;
import android.content.SharedPreferences;

import com.vibeathon.core.DeliveryFailureMode;
import com.vibeathon.core.DeliveryStrategy;
import com.vibeathon.core.RecordingLimits;

/** Typed wrapper around the app's SharedPreferences. */
public final class AppSettings {

    private static final String PREFS = "vibeathon_settings";
    private static final String KEY_STRATEGY = "strategy";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_AUTO_READ = "auto_read_aloud";
    private static final String KEY_REPLY_TIMEOUT = "reply_timeout_seconds";
    private static final String KEY_MAX_RECORDING = "max_recording_seconds";
    private static final String KEY_FAILURE_MODE = "failure_mode";
    private static final String KEY_LAST_RECORDING = "last_recording_path";
    private static final String KEY_CHOOSER_HINT_SHOWN = "chooser_hint_shown";

    public static final int DEFAULT_REPLY_TIMEOUT_SECONDS = 180;
    public static final int MIN_REPLY_TIMEOUT_SECONDS = 10;
    public static final int MAX_REPLY_TIMEOUT_SECONDS = 1800;

    private final SharedPreferences prefs;
    private final String defaultPrompt;

    public AppSettings(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        defaultPrompt = app.getString(R.string.default_prompt);
    }

    public DeliveryStrategy strategy() {
        String value = prefs.getString(KEY_STRATEGY, DeliveryStrategy.SHARE.name());
        try {
            return DeliveryStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            return DeliveryStrategy.SHARE;
        }
    }

    public void setStrategy(DeliveryStrategy strategy) {
        prefs.edit().putString(KEY_STRATEGY, strategy.name()).apply();
    }

    public DeliveryFailureMode failureMode() {
        String value = prefs.getString(KEY_FAILURE_MODE, DeliveryFailureMode.FALLBACK_CHAIN.name());
        try {
            return DeliveryFailureMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return DeliveryFailureMode.FALLBACK_CHAIN;
        }
    }

    public void setFailureMode(DeliveryFailureMode mode) {
        prefs.edit().putString(KEY_FAILURE_MODE, mode.name()).apply();
    }

    /** Absolute path of the last recording that could not be delivered, or null. */
    public String lastRecordingPath() {
        return prefs.getString(KEY_LAST_RECORDING, null);
    }

    public void setLastRecordingPath(String path) {
        prefs.edit().putString(KEY_LAST_RECORDING, path).apply();
    }

    /** True once the explanatory message for the system chooser has been shown. */
    public boolean chooserHintShown() {
        return prefs.getBoolean(KEY_CHOOSER_HINT_SHOWN, false);
    }

    public void setChooserHintShown(boolean shown) {
        prefs.edit().putBoolean(KEY_CHOOSER_HINT_SHOWN, shown).apply();
    }

    public String prompt() {
        String value = prefs.getString(KEY_PROMPT, null);
        return value == null || value.trim().isEmpty() ? defaultPrompt : value;
    }

    public void setPrompt(String prompt) {
        prefs.edit().putString(KEY_PROMPT, prompt == null ? null : prompt.trim()).apply();
    }

    public boolean autoReadAloud() {
        return prefs.getBoolean(KEY_AUTO_READ, true);
    }

    public void setAutoReadAloud(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_READ, enabled).apply();
    }

    public int replyTimeoutSeconds() {
        return clampTimeout(prefs.getInt(KEY_REPLY_TIMEOUT, DEFAULT_REPLY_TIMEOUT_SECONDS));
    }

    public void setReplyTimeoutSeconds(int seconds) {
        prefs.edit().putInt(KEY_REPLY_TIMEOUT, clampTimeout(seconds)).apply();
    }

    public int maxRecordingSeconds() {
        return RecordingLimits.clampMaxSeconds(
                prefs.getInt(KEY_MAX_RECORDING, RecordingLimits.DEFAULT_MAX_SECONDS));
    }

    public void setMaxRecordingSeconds(int seconds) {
        prefs.edit().putInt(KEY_MAX_RECORDING, RecordingLimits.clampMaxSeconds(seconds)).apply();
    }

    private static int clampTimeout(int seconds) {
        return Math.max(MIN_REPLY_TIMEOUT_SECONDS, Math.min(MAX_REPLY_TIMEOUT_SECONDS, seconds));
    }
}
