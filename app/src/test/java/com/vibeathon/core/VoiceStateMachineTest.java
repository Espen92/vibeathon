package com.vibeathon.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class VoiceStateMachineTest {

    private VoiceStateMachine machine;
    private List<String> transitions;

    @Before
    public void setUp() {
        machine = new VoiceStateMachine();
        transitions = new ArrayList<>();
        machine.setListener((from, to, message) -> transitions.add(from + "->" + to));
    }

    @Test
    public void happyPathWithReadAloud() {
        assertEquals(VoiceState.IDLE, machine.state());
        assertEquals(VoiceStateMachine.TapAction.START_RECORDING, machine.onTap());
        assertEquals(VoiceState.RECORDING, machine.state());
        assertEquals(VoiceStateMachine.TapAction.STOP_AND_SEND, machine.onTap());
        assertEquals(VoiceState.SENDING, machine.state());
        assertTrue(machine.onSent());
        assertEquals(VoiceState.AWAITING_REPLY, machine.state());
        assertTrue(machine.onReplyComplete(true));
        assertEquals(VoiceState.READING, machine.state());
        assertTrue(machine.onReadingFinished());
        assertEquals(VoiceState.IDLE, machine.state());
        assertEquals(List.of("IDLE->RECORDING", "RECORDING->SENDING", "SENDING->AWAITING_REPLY",
                "AWAITING_REPLY->READING", "READING->IDLE"), transitions);
    }

    @Test
    public void replyCompleteWithoutAutoReadGoesIdle() {
        machine.onTap();
        machine.onTap();
        machine.onSent();
        assertTrue(machine.onReplyComplete(false));
        assertEquals(VoiceState.IDLE, machine.state());
    }

    @Test
    public void tapWhileSendingIsIgnored() {
        machine.onTap();
        machine.onTap();
        assertEquals(VoiceStateMachine.TapAction.IGNORE, machine.onTap());
        assertEquals(VoiceState.SENDING, machine.state());
    }

    @Test
    public void bargeInWhileAwaitingReplyStartsNewRecording() {
        machine.onTap();
        machine.onTap();
        machine.onSent();
        int session = machine.session();
        assertEquals(VoiceStateMachine.TapAction.BARGE_IN, machine.onTap());
        assertEquals(VoiceState.RECORDING, machine.state());
        assertNotEquals(session, machine.session());
        // Stale completion callback from the old session must not advance the new one.
        assertFalse(machine.isCurrent(session, VoiceState.AWAITING_REPLY));
        assertFalse(machine.onReplyComplete(true));
        assertEquals(VoiceState.RECORDING, machine.state());
    }

    @Test
    public void bargeInWhileReadingStartsNewRecording() {
        machine.onTap();
        machine.onTap();
        machine.onSent();
        machine.onReplyComplete(true);
        assertEquals(VoiceStateMachine.TapAction.BARGE_IN, machine.onTap());
        assertEquals(VoiceState.RECORDING, machine.state());
    }

    @Test
    public void errorReachableFromAnyStateAndTapRecovers() {
        for (VoiceState s : VoiceState.values()) {
            assertTrue(VoiceStateMachine.isAllowed(s, VoiceState.ERROR));
        }
        machine.onTap();
        machine.onError("boom");
        assertEquals(VoiceState.ERROR, machine.state());
        assertEquals("boom", machine.message());
        assertEquals(VoiceStateMachine.TapAction.START_RECORDING, machine.onTap());
        assertEquals(VoiceState.RECORDING, machine.state());
    }

    @Test
    public void discardedRecordingReturnsToIdleWithHint() {
        machine.onTap();
        machine.onTap();
        assertTrue(machine.onRecordingDiscarded("too short"));
        assertEquals(VoiceState.IDLE, machine.state());
        assertEquals("too short", machine.message());
        assertFalse(machine.onRecordingDiscarded("again"));
    }

    @Test
    public void recordingStoppedExternally() {
        assertFalse(machine.onRecordingStopped());
        machine.onTap();
        assertTrue(machine.onRecordingStopped());
        assertEquals(VoiceState.SENDING, machine.state());
    }

    @Test
    public void invalidTransitionsAreRejected() {
        assertFalse(machine.onSent());
        assertFalse(machine.onReplyComplete(true));
        assertFalse(machine.onReadingFinished());
        assertFalse(VoiceStateMachine.isAllowed(VoiceState.IDLE, VoiceState.SENDING));
        assertFalse(VoiceStateMachine.isAllowed(VoiceState.RECORDING, VoiceState.READING));
        assertFalse(VoiceStateMachine.isAllowed(VoiceState.SENDING, VoiceState.RECORDING));
        assertTrue(transitions.isEmpty());
    }

    @Test
    public void cancelReturnsToIdle() {
        machine.onTap();
        machine.cancel();
        assertEquals(VoiceState.IDLE, machine.state());
    }

    @Test
    public void recordingLimits() {
        assertTrue(RecordingLimits.isTooShort(499));
        assertFalse(RecordingLimits.isTooShort(500));
        assertEquals(5, RecordingLimits.clampMaxSeconds(0));
        assertEquals(300, RecordingLimits.clampMaxSeconds(300));
        assertEquals("1:05", RecordingLimits.formatElapsed(65_000));
        assertEquals("0:00", RecordingLimits.formatElapsed(-5));
    }
}
