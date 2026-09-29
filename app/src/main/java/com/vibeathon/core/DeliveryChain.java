package com.vibeathon.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs delivery steps in order until one succeeds. Steps are asynchronous: the runner reports
 * back through {@link StepCallback}, and each callback is accepted only once so a late
 * duplicate cannot advance the chain twice.
 *
 * <p>Not thread safe: {@link #run} and every callback must be invoked on the same thread
 * (the main thread in the app, where both the overlay and the accessibility service run).
 */
public final class DeliveryChain {

    public interface StepCallback {
        void onSuccess();

        /** @param reason short, user-visible reason why this step failed */
        void onFailure(String reason);
    }

    public interface StepRunner {
        void run(DeliveryPlan.Step step, StepCallback callback);
    }

    public interface Outcome {
        void onDelivered(DeliveryPlan.Step step);

        /**
         * @param lastStep the step that failed last, may be null when the plan was empty
         * @param lastReason the reason reported by that step
         * @param failures one "step: reason" entry per attempt, for the debug log
         */
        void onExhausted(DeliveryPlan.Step lastStep, String lastReason, List<String> failures);
    }

    private final List<DeliveryPlan.Step> steps;
    private final StepRunner runner;
    private final Outcome outcome;
    private final List<String> failures = new ArrayList<>();
    private int index;
    private boolean finished;

    private DeliveryChain(List<DeliveryPlan.Step> steps, StepRunner runner, Outcome outcome) {
        this.steps = new ArrayList<>(steps);
        this.runner = runner;
        this.outcome = outcome;
    }

    public static void run(List<DeliveryPlan.Step> steps, StepRunner runner, Outcome outcome) {
        new DeliveryChain(steps, runner, outcome).next(null, null);
    }

    private void next(DeliveryPlan.Step lastStep, String lastReason) {
        if (finished) {
            return;
        }
        if (index >= steps.size()) {
            finished = true;
            outcome.onExhausted(lastStep, lastReason, new ArrayList<>(failures));
            return;
        }
        final DeliveryPlan.Step step = steps.get(index++);
        runner.run(step, new StepCallback() {
            private boolean used;

            @Override
            public void onSuccess() {
                if (used || finished) {
                    return;
                }
                used = true;
                finished = true;
                outcome.onDelivered(step);
            }

            @Override
            public void onFailure(String reason) {
                if (used || finished) {
                    return;
                }
                used = true;
                failures.add(step + ": " + reason);
                next(step, reason);
            }
        });
    }
}
