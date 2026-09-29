package com.vibeathon.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Android-free part of the share-target probe: which MIME types to try for an audio share,
 * in which order, and how to pick the first one an app actually accepts.
 *
 * <p>ChatGPT does not declare a share intent filter for every audio MIME type, so a plain
 * {@code ACTION_SEND} with {@code audio/mp4} can resolve to nothing. The probe asks the
 * package manager for each candidate type and reports what was found.
 */
public final class ShareProbe {

    /** Candidate MIME types, most specific first. */
    public static final List<String> MIME_TYPES = Collections.unmodifiableList(Arrays.asList(
            "audio/mp4",
            "audio/m4a",
            "audio/*",
            "application/octet-stream",
            "*/*"));

    /**
     * Resolves a MIME type to the component names that can receive it. A null or empty list
     * means the MIME type has no share target.
     */
    public interface Resolver {
        List<String> resolve(String mimeType);
    }

    /** One probed MIME type and the components that accepted it. */
    public static final class Result {
        public final String mimeType;
        public final List<String> components;

        public Result(String mimeType, List<String> components) {
            this.mimeType = mimeType;
            this.components = Collections.unmodifiableList(new ArrayList<>(components));
        }

        public boolean supported() {
            return !components.isEmpty();
        }

        @Override
        public String toString() {
            return mimeType + " -> " + (components.isEmpty() ? "(none)" : components);
        }
    }

    private ShareProbe() {
    }

    /** Probes every candidate MIME type in order. */
    public static List<Result> probe(Resolver resolver) {
        List<Result> results = new ArrayList<>(MIME_TYPES.size());
        for (String mimeType : MIME_TYPES) {
            List<String> components = resolver.resolve(mimeType);
            results.add(new Result(mimeType,
                    components == null ? Collections.emptyList() : components));
        }
        return results;
    }

    /** Returns the first MIME type with at least one share target, or null when there is none. */
    public static String firstSupportedMimeType(List<Result> results) {
        for (Result result : results) {
            if (result.supported()) {
                return result.mimeType;
            }
        }
        return null;
    }
}
