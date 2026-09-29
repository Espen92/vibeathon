package com.vibeathon.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SelectorsTest {

    @Test
    public void matchesIgnoringCaseAndWhitespace() {
        assertTrue(Selectors.matches(Selectors.READ_ALOUD, "Read aloud"));
        assertTrue(Selectors.matches(Selectors.READ_ALOUD, "  read   ALOUD "));
        assertTrue(Selectors.matches(Selectors.SEND, "Send message"));
        assertTrue(Selectors.matches(Selectors.STOP_GENERATING, "Stop generating"));
        assertTrue(Selectors.matches(Selectors.MORE_ACTIONS, "More actions"));
        assertTrue(Selectors.matches(Selectors.ATTACH, "Attach"));
    }

    @Test
    public void doesNotMatchPartialOrEmptyLabels() {
        assertFalse(Selectors.matches(Selectors.SEND, null));
        assertFalse(Selectors.matches(Selectors.SEND, ""));
        assertFalse(Selectors.matches(Selectors.SEND, "Sending…"));
        assertFalse(Selectors.matches(Selectors.READ_ALOUD, "Read aloud this message please"));
    }

    @Test
    public void matchesAnyChecksDescriptionAndText() {
        assertTrue(Selectors.matchesAny(Selectors.SEND, null, "Send"));
        assertTrue(Selectors.matchesAny(Selectors.SEND, "Send", null));
        assertFalse(Selectors.matchesAny(Selectors.SEND, "Copy", "Edit"));
    }
}
