package com.vibeathon.core;

/** Injectable monotonic clock so time-based logic can be unit tested. */
public interface Clock {
    long nowMillis();
}
