package com.vibeathon;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Fallback: on-device speech-to-text with {@link SpeechRecognizer}. Recognition sessions end
 * on silence, so they are restarted until {@link #stop} is called and the text is accumulated.
 */
public final class Transcriber {

    public interface Callback {
        void onTranscript(String text);

        void onError(String message);
    }

    private static final long FINISH_GRACE_MS = 4000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private final StringBuilder transcript = new StringBuilder();
    private String pendingPartial = "";
    private boolean active;
    private boolean stopping;
    private Callback stopCallback;
    private Callback failureCallback;

    public Transcriber(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context));
    }

    public boolean isActive() {
        return active;
    }

    /** @param failureCallback notified if recognition fails before {@link #stop} is called */
    public void start(Callback failureCallback) {
        if (!isAvailable(context)) {
            throw new IllegalStateException("Speech recognition is not available");
        }
        transcript.setLength(0);
        pendingPartial = "";
        stopping = false;
        stopCallback = null;
        this.failureCallback = failureCallback;
        active = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            DebugLog.log("Transcriber: using on-device recognizer");
        } else {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            DebugLog.log("Transcriber: using default recognizer (prefer offline)");
        }
        recognizer.setRecognitionListener(listener);
        listen();
    }

    /** Stops listening and delivers the accumulated transcript to {@code callback}. */
    public void stop(Callback callback) {
        if (!active) {
            callback.onError("Not transcribing");
            return;
        }
        stopping = true;
        stopCallback = callback;
        try {
            recognizer.stopListening();
        } catch (RuntimeException e) {
            DebugLog.error("stopListening failed", e);
        }
        handler.postDelayed(this::finish, FINISH_GRACE_MS);
    }

    public void cancel() {
        stopCallback = null;
        failureCallback = null;
        release();
    }

    private void listen() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                10000);
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            DebugLog.error("startListening failed", e);
            fail("Speech recognizer failed to start");
        }
    }

    private void append(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (transcript.length() > 0) {
            transcript.append(' ');
        }
        transcript.append(text.trim());
    }

    private void finish() {
        if (!active) {
            return;
        }
        if (!pendingPartial.isEmpty()) {
            append(pendingPartial);
            pendingPartial = "";
        }
        Callback cb = stopCallback;
        String text = transcript.toString().trim();
        release();
        if (cb != null) {
            if (text.isEmpty()) {
                cb.onError(context.getString(R.string.error_no_speech));
            } else {
                cb.onTranscript(text);
            }
        }
    }

    private void fail(String message) {
        Callback cb = stopCallback != null ? stopCallback : failureCallback;
        release();
        if (cb != null) {
            cb.onError(message);
        }
    }

    private void release() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException e) {
                DebugLog.error("Recognizer destroy failed", e);
            }
            recognizer = null;
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            DebugLog.log("Transcriber error code " + error);
            if (!active) {
                return;
            }
            if (stopping) {
                finish();
            } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (!pendingPartial.isEmpty()) {
                    append(pendingPartial);
                    pendingPartial = "";
                }
                listen();
            } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                fail(context.getString(R.string.error_mic_permission));
            }
            // Other errors (e.g. network/busy) are reported when the user stops.
        }

        @Override
        public void onResults(Bundle results) {
            if (!active) {
                return;
            }
            ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            pendingPartial = "";
            if (matches != null && !matches.isEmpty()) {
                append(matches.get(0));
            }
            if (stopping) {
                finish();
            } else {
                listen();
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches =
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty() && matches.get(0) != null) {
                pendingPartial = matches.get(0);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
