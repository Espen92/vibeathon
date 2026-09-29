package com.vibeathon.core;

/** States of the hands-free voice flow. */
public enum VoiceState {
    IDLE,
    RECORDING,
    SENDING,
    AWAITING_REPLY,
    READING,
    ERROR
}
