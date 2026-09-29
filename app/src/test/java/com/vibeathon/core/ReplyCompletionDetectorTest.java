package com.vibeathon.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ReplyCompletionDetectorTest {

    private static final class FakeClock implements Clock {
        long now = 1_000;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    private FakeClock clock;
    private ReplyCompletionDetector detector;

    @Before
    public void setUp() {
        clock = new FakeClock();
        detector = new ReplyCompletionDetector(clock, 1500, 180_000);
        detector.start(1, "old answer");
    }

    @Test
    public void completesAfterStopDisappearsAndTextIsStable() {
        assertEquals(ReplyCompletionDetector.Status.WAITING_FOR_START,
                detector.update(false, "old answer", 1));
        clock.now += 500;
        assertEquals(ReplyCompletionDetector.Status.GENERATING, detector.update(true, "Hel", 0));
        clock.now += 500;
        assertEquals(ReplyCompletionDetector.Status.GENERATING, detector.update(true, "Hello", 0));
        clock.now += 250;
        assertEquals(ReplyCompletionDetector.Status.SETTLING, detector.update(false, "Hello!", 1));
        clock.now += 1499;
        assertEquals(ReplyCompletionDetector.Status.SETTLING, detector.update(false, "Hello!", 1));
        clock.now += 1;
        assertEquals(ReplyCompletionDetector.Status.COMPLETE, detector.update(false, "Hello!", 1));
        assertTrue(detector.isFinished());
    }

    @Test
    public void textChangeRestartsStabilityWindow() {
        detector.update(true, "a", 0);
        clock.now += 1000;
        detector.update(false, "ab", 0);
        clock.now += 1000;
        assertEquals(ReplyCompletionDetector.Status.SETTLING, detector.update(false, "abc", 0));
        clock.now += 1000;
        assertEquals(ReplyCompletionDetector.Status.SETTLING, detector.update(false, "abc", 0));
        clock.now += 500;
        assertEquals(ReplyCompletionDetector.Status.COMPLETE, detector.update(false, "abc", 0));
    }

    @Test
    public void doesNotCompleteBeforeGenerationStarts() {
        // Only the user's own message appears; no stop control and no new response actions.
        detector.update(false, "respond to the prompt in the voice file", 1);
        clock.now += 10_000;
        assertEquals(ReplyCompletionDetector.Status.WAITING_FOR_START,
                detector.update(false, "respond to the prompt in the voice file", 1));
        assertFalse(detector.isFinished());
    }

    @Test
    public void newResponseActionCountsAsStarted() {
        // Reply was so fast that the stop control was never observed.
        detector.update(false, "quick reply", 2);
        clock.now += 1500;
        assertEquals(ReplyCompletionDetector.Status.COMPLETE,
                detector.update(false, "quick reply", 2));
    }

    @Test
    public void stopControlVisibleNeverCompletes() {
        detector.update(true, "x", 0);
        clock.now += 10_000;
        assertEquals(ReplyCompletionDetector.Status.GENERATING, detector.update(true, "x", 0));
    }

    @Test
    public void timesOut() {
        detector.update(true, "x", 0);
        clock.now += 180_000;
        assertEquals(ReplyCompletionDetector.Status.TIMED_OUT, detector.update(true, "x", 0));
        clock.now += 5_000;
        assertEquals(ReplyCompletionDetector.Status.TIMED_OUT, detector.update(false, "x", 5));
    }

    @Test
    public void emptyTextIsNotComplete() {
        detector.update(true, "", 0);
        clock.now += 5_000;
        assertEquals(ReplyCompletionDetector.Status.SETTLING, detector.update(false, " ", 0));
    }

    @Test
    public void restartResetsState() {
        detector.update(true, "x", 0);
        clock.now += 2_000;
        detector.update(false, "x", 0);
        assertTrue(detector.isFinished());
        detector.start(3, "x");
        assertEquals(ReplyCompletionDetector.Status.WAITING_FOR_START, detector.status());
        clock.now += 2_000;
        assertEquals(ReplyCompletionDetector.Status.WAITING_FOR_START,
                detector.update(false, "x", 3));
    }
}
