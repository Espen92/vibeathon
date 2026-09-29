package com.vibeathon.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ShareProbeTest {

    /** Fake package manager: only the listed MIME types have a share target. */
    private static ShareProbe.Resolver resolverFor(List<String> supported, List<String> probed) {
        return mimeType -> {
            probed.add(mimeType);
            return supported.contains(mimeType)
                    ? Collections.singletonList("com.openai.chatgpt/.ShareActivity")
                    : Collections.emptyList();
        };
    }

    @Test
    public void probesMimeTypesInMostSpecificFirstOrder() {
        List<String> probed = new ArrayList<>();
        List<ShareProbe.Result> results =
                ShareProbe.probe(resolverFor(Collections.emptyList(), probed));

        assertEquals(Arrays.asList("audio/mp4", "audio/m4a", "audio/*",
                "application/octet-stream", "*/*"), probed);
        assertEquals(ShareProbe.MIME_TYPES.size(), results.size());
        assertEquals("audio/mp4", results.get(0).mimeType);
        assertFalse(results.get(0).supported());
    }

    @Test
    public void firstSupportedMimeTypeSkipsUnsupportedTypes() {
        List<ShareProbe.Result> results = ShareProbe.probe(
                resolverFor(Arrays.asList("audio/*", "*/*"), new ArrayList<>()));

        assertEquals("audio/*", ShareProbe.firstSupportedMimeType(results));
        assertTrue(results.get(2).supported());
        assertEquals(Collections.singletonList("com.openai.chatgpt/.ShareActivity"),
                results.get(2).components);
    }

    @Test
    public void firstSupportedMimeTypeIsNullWhenNothingAcceptsAudio() {
        List<ShareProbe.Result> results =
                ShareProbe.probe(resolverFor(Collections.emptyList(), new ArrayList<>()));

        assertNull(ShareProbe.firstSupportedMimeType(results));
    }

    @Test
    public void nullComponentListIsTreatedAsUnsupported() {
        List<ShareProbe.Result> results = ShareProbe.probe(mimeType -> null);

        assertNull(ShareProbe.firstSupportedMimeType(results));
        for (ShareProbe.Result result : results) {
            assertTrue(result.components.isEmpty());
        }
    }
}
