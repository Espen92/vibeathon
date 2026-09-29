package com.vibeathon.core;

/** How the recorded voice memo should be delivered to ChatGPT. */
public enum DeliveryStrategy {
    /** ACTION_SEND the audio file to ChatGPT, then press Send via accessibility. */
    SHARE,
    /** Attach the file through ChatGPT's own attachment menu via accessibility. */
    ACCESSIBILITY,
    /** Transcribe on-device with SpeechRecognizer and send text instead of a file. */
    TRANSCRIBE
}
