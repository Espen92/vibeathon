package com.vibeathon.core;

/** What to do when the preferred delivery step fails. */
public enum DeliveryFailureMode {
    /** Try every remaining delivery step in order (default). */
    FALLBACK_CHAIN,
    /** Skip the automated steps and always open the system chooser. */
    CHOOSER_ONLY,
    /** Only run the configured strategy and show an error when it fails. */
    FAIL_FAST
}
