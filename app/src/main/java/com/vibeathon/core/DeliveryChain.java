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
    private boolean running;
    private boolean pending;
    private DeliveryPlan.Step pendingStep;
    private String pendingReason;

    private DeliveryChain(List<DeliveryPlan.Step> steps, StepRunner runner, Outcome outcome) {
        this.steps = new ArrayList<>(steps);
        this.runner = runner;
        this.outcome = outcome;
    }

    public static void run(List<DeliveryPlan.Step> steps, StepRunner runner, Outcome outcome) {
        new DeliveryChain(steps, runner, outcome).next(null, null);
    }

    /**
     * Starts the next step. Steps that fail synchronously are handled iteratively (the
     * {@code running} flag turns the re-entrant call into another loop iteration) so that a
     * long plan cannot grow the stack.
     */
    private void next(DeliveryPlan.Step lastStep, String lastReason) {
        if (running) {
            pending = true;
            pendingStep = lastStep;
            pendingReason = lastReason;
            return;
        }
        running = true;
        try {
            DeliveryPlan.Step previous = lastStep;
            String reason = lastReason;
            do {
                pending = false;
                if (finished) {
                    return;
                }
                if (index >= steps.size()) {
                    finished = true;
                    outcome.onExhausted(previous, reason, new ArrayList<>(failures));
                    return;
                }
                runStep(steps.get(index++));
                previous = pendingStep;
                reason = pendingReason;
            } while (pending);
        } finally {
            running = false;
        }
    }

    private void runStep(final DeliveryPlan.Step step) {
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
