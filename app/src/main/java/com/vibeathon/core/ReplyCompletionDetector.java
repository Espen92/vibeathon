package com.vibeathon.core;

/**
 * Decides when ChatGPT has finished writing its reply.
 *
 * <p>A reply is complete when:
 * <ol>
 *   <li>generation has started - the "Stop generating" control was seen, or the number of
 *       assistant-response action buttons (e.g. "Read aloud", "Good response") grew compared to
 *       the baseline captured before sending;</li>
 *   <li>the "Stop generating" control is no longer visible; and</li>
 *   <li>the latest assistant text has not changed for {@code stableMillis} (default 1.5 s).</li>
 * </ol>
 * If this does not happen within {@code timeoutMillis} (default 180 s) the result is
 * {@link Status#TIMED_OUT}.
 */
public final class ReplyCompletionDetector {

    public static final long DEFAULT_STABLE_MILLIS = 1500;
    public static final long DEFAULT_TIMEOUT_MILLIS = 180_000;

    public enum Status {
        WAITING_FOR_START,
        GENERATING,
        SETTLING,
        COMPLETE,
        TIMED_OUT
    }

    private final Clock clock;
    private final long stableMillis;
    private final long timeoutMillis;

    private long startedAt;
    private long lastChangeAt;
    private String lastText;
    private int baselineActionCount;
    private boolean sawGenerating;
    private Status status = Status.WAITING_FOR_START;

    public ReplyCompletionDetector(Clock clock, long stableMillis, long timeoutMillis) {
        if (clock == null) {
            throw new IllegalArgumentException("clock == null");
        }
        this.clock = clock;
        this.stableMillis = stableMillis;
        this.timeoutMillis = timeoutMillis;
        start(0, null);
    }

    public ReplyCompletionDetector(Clock clock) {
        this(clock, DEFAULT_STABLE_MILLIS, DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Resets the detector. Call right after pressing Send.
     *
     * @param baselineActionCount number of assistant-response action buttons visible before sending
     * @param baselineText latest assistant text visible before sending (may be null)
     */
    public void start(int baselineActionCount, String baselineText) {
        long now = clock.nowMillis();
        this.startedAt = now;
        this.lastChangeAt = now;
        this.lastText = baselineText;
        this.baselineActionCount = baselineActionCount;
        this.sawGenerating = false;
        this.status = Status.WAITING_FOR_START;
    }

    public Status status() {
        return status;
    }

    public boolean isFinished() {
        return status == Status.COMPLETE || status == Status.TIMED_OUT;
    }

    /**
     * Feeds a snapshot of the ChatGPT UI.
     *
     * @param stopControlVisible whether a "Stop generating" control is visible
     * @param latestText text of the newest assistant message (or visible conversation tail)
     * @param responseActionCount number of assistant-response action buttons visible
     */
    public Status update(boolean stopControlVisible, String latestText, int responseActionCount) {
        if (isFinished()) {
            return status;
        }
        long now = clock.nowMillis();
        if (!equals(latestText, lastText)) {
            lastText = latestText;
            lastChangeAt = now;
        }
        if (stopControlVisible) {
            sawGenerating = true;
            lastChangeAt = now;
        }
        boolean started = sawGenerating || responseActionCount > baselineActionCount;
        boolean hasText = latestText != null && !latestText.trim().isEmpty();

        if (started && !stopControlVisible && hasText && now - lastChangeAt >= stableMillis) {
            status = Status.COMPLETE;
        } else if (now - startedAt >= timeoutMillis) {
            status = Status.TIMED_OUT;
        } else if (!started) {
            status = Status.WAITING_FOR_START;
        } else if (stopControlVisible) {
            status = Status.GENERATING;
        } else {
            status = Status.SETTLING;
        }
        return status;
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
