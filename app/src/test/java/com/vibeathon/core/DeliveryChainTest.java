package com.vibeathon.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.vibeathon.core.DeliveryPlan.Step;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DeliveryChainTest {

    /** Fake runner that fails every step it is given, recording the order. */
    private static DeliveryChain.StepRunner failing(List<Step> attempted) {
        return (step, callback) -> {
            attempted.add(step);
            callback.onFailure("no " + step);
        };
    }

    private static final class RecordingOutcome implements DeliveryChain.Outcome {
        Step delivered;
        Step lastStep;
        String lastReason;
        List<String> failures;
        int exhaustedCount;

        @Override
        public void onDelivered(Step step) {
            delivered = step;
        }

        @Override
        public void onExhausted(Step step, String reason, List<String> allFailures) {
            exhaustedCount++;
            lastStep = step;
            lastReason = reason;
            failures = allFailures;
        }
    }

    @Test
    public void shareStrategyFallsBackThroughEveryStep() {
        assertEquals(Arrays.asList(Step.DIRECT_SHARE, Step.ACCESSIBILITY_ATTACH, Step.CHOOSER,
                        Step.TRANSCRIBE),
                DeliveryPlan.plan(DeliveryStrategy.SHARE, DeliveryFailureMode.FALLBACK_CHAIN));
    }

    @Test
    public void accessibilityStrategyTriesItsOwnStepFirst() {
        assertEquals(Arrays.asList(Step.ACCESSIBILITY_ATTACH, Step.DIRECT_SHARE, Step.CHOOSER,
                        Step.TRANSCRIBE),
                DeliveryPlan.plan(DeliveryStrategy.ACCESSIBILITY,
                        DeliveryFailureMode.FALLBACK_CHAIN));
    }

    @Test
    public void chooserOnlyAndFailFastShortenThePlan() {
        assertEquals(Collections.singletonList(Step.CHOOSER),
                DeliveryPlan.plan(DeliveryStrategy.SHARE, DeliveryFailureMode.CHOOSER_ONLY));
        assertEquals(Collections.singletonList(Step.DIRECT_SHARE),
                DeliveryPlan.plan(DeliveryStrategy.SHARE, DeliveryFailureMode.FAIL_FAST));
        assertEquals(Collections.singletonList(Step.ACCESSIBILITY_ATTACH),
                DeliveryPlan.plan(DeliveryStrategy.ACCESSIBILITY,
                        DeliveryFailureMode.FAIL_FAST));
    }

    @Test
    public void transcribeStrategyIgnoresTheFallbackChain() {
        for (DeliveryFailureMode mode : DeliveryFailureMode.values()) {
            assertEquals(Collections.singletonList(Step.TRANSCRIBE),
                    DeliveryPlan.plan(DeliveryStrategy.TRANSCRIBE, mode));
        }
    }

    @Test
    public void runsStepsInOrderUntilOneSucceeds() {
        List<Step> attempted = new ArrayList<>();
        RecordingOutcome outcome = new RecordingOutcome();

        DeliveryChain.run(DeliveryPlan.plan(DeliveryStrategy.SHARE,
                DeliveryFailureMode.FALLBACK_CHAIN), (step, callback) -> {
                    attempted.add(step);
                    if (step == Step.CHOOSER) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure("no " + step);
                    }
                }, outcome);

        assertEquals(Arrays.asList(Step.DIRECT_SHARE, Step.ACCESSIBILITY_ATTACH, Step.CHOOSER),
                attempted);
        assertEquals(Step.CHOOSER, outcome.delivered);
        assertEquals(0, outcome.exhaustedCount);
    }

    @Test
    public void reportsEveryFailureWhenAllStepsFail() {
        List<Step> attempted = new ArrayList<>();
        RecordingOutcome outcome = new RecordingOutcome();

        DeliveryChain.run(DeliveryPlan.plan(DeliveryStrategy.SHARE,
                DeliveryFailureMode.FALLBACK_CHAIN), failing(attempted), outcome);

        assertEquals(4, attempted.size());
        assertEquals(1, outcome.exhaustedCount);
        assertEquals(Step.TRANSCRIBE, outcome.lastStep);
        assertEquals("no TRANSCRIBE", outcome.lastReason);
        assertEquals(4, outcome.failures.size());
        assertTrue(outcome.failures.get(0).startsWith("DIRECT_SHARE: "));
        assertNull(outcome.delivered);
    }

    @Test
    public void failFastStopsAfterTheConfiguredStep() {
        List<Step> attempted = new ArrayList<>();
        RecordingOutcome outcome = new RecordingOutcome();

        DeliveryChain.run(DeliveryPlan.plan(DeliveryStrategy.SHARE,
                DeliveryFailureMode.FAIL_FAST), failing(attempted), outcome);

        assertEquals(Collections.singletonList(Step.DIRECT_SHARE), attempted);
        assertEquals(Step.DIRECT_SHARE, outcome.lastStep);
    }

    @Test
    public void lateDuplicateCallbacksAreIgnored() {
        List<Step> attempted = new ArrayList<>();
        RecordingOutcome outcome = new RecordingOutcome();

        DeliveryChain.run(DeliveryPlan.plan(DeliveryStrategy.SHARE,
                DeliveryFailureMode.FALLBACK_CHAIN), (step, callback) -> {
                    attempted.add(step);
                    callback.onFailure("first");
                    // A timeout firing after the step already reported must not re-enter.
                    callback.onFailure("late");
                    callback.onSuccess();
                }, outcome);

        assertEquals(4, attempted.size());
        assertEquals(1, outcome.exhaustedCount);
        assertNull(outcome.delivered);
    }

    @Test
    public void emptyPlanIsReportedAsExhausted() {
        RecordingOutcome outcome = new RecordingOutcome();

        DeliveryChain.run(Collections.emptyList(), (step, callback) -> {
            throw new AssertionError("should not run");
        }, outcome);

        assertEquals(1, outcome.exhaustedCount);
        assertNull(outcome.lastStep);
    }
}
