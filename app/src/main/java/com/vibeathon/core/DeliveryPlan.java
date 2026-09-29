package com.vibeathon.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Builds the ordered list of delivery steps for a strategy and failure mode. */
public final class DeliveryPlan {

    /** One attempt at getting the voice memo into ChatGPT. */
    public enum Step {
        /** Package-targeted ACTION_SEND using the first supported MIME type. */
        DIRECT_SHARE,
        /** Attach the file through ChatGPT's attachment menu via accessibility. */
        ACCESSIBILITY_ATTACH,
        /** System share sheet, so the user can pick ChatGPT manually. */
        CHOOSER,
        /**
         * On-device speech-to-text. A finished .m4a cannot be fed to SpeechRecognizer, so as
         * the last step of a chain this only arms the transcription fallback for the next
         * memo and always reports failure; it delivers text only when it is the whole plan
         * (the transcribe strategy, which never produces a file).
         */
        TRANSCRIBE
    }

    private DeliveryPlan() {
    }

    public static List<Step> plan(DeliveryStrategy strategy, DeliveryFailureMode mode) {
        if (strategy == DeliveryStrategy.TRANSCRIBE) {
            return list(Step.TRANSCRIBE);
        }
        if (mode == DeliveryFailureMode.CHOOSER_ONLY) {
            return list(Step.CHOOSER);
        }
        Step preferred = strategy == DeliveryStrategy.ACCESSIBILITY
                ? Step.ACCESSIBILITY_ATTACH : Step.DIRECT_SHARE;
        if (mode == DeliveryFailureMode.FAIL_FAST) {
            return list(preferred);
        }
        Step second = preferred == Step.DIRECT_SHARE
                ? Step.ACCESSIBILITY_ATTACH : Step.DIRECT_SHARE;
        return list(preferred, second, Step.CHOOSER, Step.TRANSCRIBE);
    }

    private static List<Step> list(Step... steps) {
        return Collections.unmodifiableList(Arrays.asList(steps));
    }
}
