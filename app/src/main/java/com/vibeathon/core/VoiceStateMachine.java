package com.vibeathon.core;

/**
 * Explicit, Android-free state machine for the hands-free voice flow:
 *
 * <pre>
 * IDLE -> RECORDING -> SENDING -> AWAITING_REPLY -> READING -> IDLE
 *                         \-> IDLE (recording discarded)
 * any state -> ERROR, any state -> IDLE (cancel)
 * ERROR -> RECORDING (tap), ERROR -> SENDING (retry delivery)
 * AWAITING_REPLY / READING -> RECORDING (tap = barge-in)
 * </pre>
 *
 * Every time a new recording starts the {@link #session()} counter is incremented, so
 * asynchronous callbacks that belong to an older run can detect that they are stale.
 */
public final class VoiceStateMachine {

    /** What the caller must do in response to a tap on the overlay. */
    public enum TapAction {
        START_RECORDING,
        STOP_AND_SEND,
        /** Cancel the in-flight wait/read-aloud and start a new recording. */
        BARGE_IN,
        IGNORE
    }

    public interface Listener {
        void onStateChanged(VoiceState from, VoiceState to, String message);
    }

    private VoiceState state = VoiceState.IDLE;
    private int session;
    private String message;
    private Listener listener;

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized VoiceState state() {
        return state;
    }

    /** Last error or hint message, may be null. */
    public synchronized String message() {
        return message;
    }

    public synchronized int session() {
        return session;
    }

    public synchronized boolean isCurrent(int sessionId, VoiceState expected) {
        return session == sessionId && state == expected;
    }

    public static boolean isAllowed(VoiceState from, VoiceState to) {
        if (to == VoiceState.ERROR || to == VoiceState.IDLE) {
            return true;
        }
        switch (from) {
            case IDLE:
                return to == VoiceState.RECORDING;
            case ERROR:
                // A retry re-sends the last recording without recording again.
                return to == VoiceState.RECORDING || to == VoiceState.SENDING;
            case RECORDING:
                return to == VoiceState.SENDING;
            case SENDING:
                return to == VoiceState.AWAITING_REPLY;
            case AWAITING_REPLY:
                return to == VoiceState.READING || to == VoiceState.RECORDING;
            case READING:
                return to == VoiceState.RECORDING;
            default:
                return false;
        }
    }

    public TapAction onTap() {
        VoiceState target;
        TapAction action;
        synchronized (this) {
            switch (state) {
                case IDLE:
                case ERROR:
                    target = VoiceState.RECORDING;
                    action = TapAction.START_RECORDING;
                    break;
                case RECORDING:
                    target = VoiceState.SENDING;
                    action = TapAction.STOP_AND_SEND;
                    break;
                case AWAITING_REPLY:
                case READING:
                    target = VoiceState.RECORDING;
                    action = TapAction.BARGE_IN;
                    break;
                case SENDING:
                default:
                    return TapAction.IGNORE;
            }
            if (target == VoiceState.RECORDING) {
                session++;
            }
        }
        transition(target, null);
        return action;
    }

    /** Recording stopped by something other than a tap (max length, notification). */
    public boolean onRecordingStopped() {
        return transitionFrom(VoiceState.RECORDING, VoiceState.SENDING, null);
    }

    /** Recording was too short or empty; go back to idle with a hint. */
    public boolean onRecordingDiscarded(String hint) {
        VoiceState s = state();
        if (s != VoiceState.RECORDING && s != VoiceState.SENDING) {
            return false;
        }
        return transitionFrom(s, VoiceState.IDLE, hint);
    }

    /**
     * Re-sends the last recording after a failed delivery. Starts a new session so callbacks
     * from the failed attempt are ignored.
     */
    public boolean onRetryDelivery() {
        Listener l;
        synchronized (this) {
            if (state != VoiceState.ERROR) {
                return false;
            }
            session++;
            state = VoiceState.SENDING;
            message = null;
            l = listener;
        }
        if (l != null) {
            l.onStateChanged(VoiceState.ERROR, VoiceState.SENDING, null);
        }
        return true;
    }

    public boolean onSent() {
        return transitionFrom(VoiceState.SENDING, VoiceState.AWAITING_REPLY, null);
    }

    public boolean onReplyComplete(boolean autoReadAloud) {
        return transitionFrom(VoiceState.AWAITING_REPLY,
                autoReadAloud ? VoiceState.READING : VoiceState.IDLE, null);
    }

    public boolean onReadingFinished() {
        return transitionFrom(VoiceState.READING, VoiceState.IDLE, null);
    }

    public void onError(String errorMessage) {
        transition(VoiceState.ERROR, errorMessage);
    }

    public void cancel() {
        transition(VoiceState.IDLE, null);
    }

    private boolean transitionFrom(VoiceState expected, VoiceState to, String msg) {
        synchronized (this) {
            if (state != expected) {
                return false;
            }
        }
        return transition(to, msg);
    }

    private boolean transition(VoiceState to, String msg) {
        VoiceState from;
        Listener l;
        synchronized (this) {
            from = state;
            if (!isAllowed(from, to)) {
                return false;
            }
            state = to;
            message = msg;
            l = listener;
        }
        if (l != null) {
            l.onStateChanged(from, to, msg);
        }
        return true;
    }
}
