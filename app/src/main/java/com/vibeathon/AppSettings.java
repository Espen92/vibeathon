package com.vibeathon;

import android.content.Context;
import android.content.SharedPreferences;

import com.vibeathon.core.RecordingLimits;

/** Typed wrapper around the app's SharedPreferences. */
public final class AppSettings {

    public enum Strategy {
        /** ACTION_SEND audio file to ChatGPT, then press Send via accessibility. */
        SHARE,
        /** Attach the file through ChatGPT's own attachment menu via accessibility. */
        ACCESSIBILITY,
        /** Transcribe on-device with SpeechRecognizer and send text instead of a file. */
        TRANSCRIBE
    }

    private static final String PREFS = "vibeathon_settings";
    private static final String KEY_STRATEGY = "strategy";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_AUTO_READ = "auto_read_aloud";
    private static final String KEY_REPLY_TIMEOUT = "reply_timeout_seconds";
    private static final String KEY_MAX_RECORDING = "max_recording_seconds";

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

    public Strategy strategy() {
        String value = prefs.getString(KEY_STRATEGY, Strategy.SHARE.name());
        try {
            return Strategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Strategy.SHARE;
        }
    }

    public void setStrategy(Strategy strategy) {
        prefs.edit().putString(KEY_STRATEGY, strategy.name()).apply();
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
