package com.vibeathon.core;

/** Pure recording-length rules shared by the recorder and tests. */
public final class RecordingLimits {

    public static final long MIN_RECORDING_MILLIS = 500;
    public static final int DEFAULT_MAX_SECONDS = 300;
    public static final int MIN_MAX_SECONDS = 5;
    public static final int MAX_MAX_SECONDS = 3600;

    private RecordingLimits() {
    }

    public static boolean isTooShort(long durationMillis) {
        return durationMillis < MIN_RECORDING_MILLIS;
    }

    public static int clampMaxSeconds(int seconds) {
        return Math.max(MIN_MAX_SECONDS, Math.min(MAX_MAX_SECONDS, seconds));
    }

    /** Formats elapsed time as m:ss for the bubble. */
    public static String formatElapsed(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
