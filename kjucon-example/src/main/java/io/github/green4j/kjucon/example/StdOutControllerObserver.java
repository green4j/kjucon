/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.github.green4j.kjucon.ControllerObserver;
import io.github.green4j.kjucon.ReconcileRequest;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Prints what the controllers do - where a real operator would count it into its metrics.
 */
public class StdOutControllerObserver implements ControllerObserver {

    @Override
    public void onReconcileFinished(final String controller,
                                    final ReconcileRequest request,
                                    final Outcome outcome,
                                    final long durationNanos) {
        System.out.printf("[%s] reconciled %s: %s in %d ms%n",
                controller, request, outcome, durationNanos / 1_000_000);
    }

    @Override
    public void onCleanupFinished(final String controller,
                                  final ReconcileRequest request,
                                  final Outcome outcome,
                                  final long durationNanos) {
        System.out.printf("[%s] cleaned up %s: %s%n", controller, request, outcome);
    }

    @Override
    public void onRetryScheduled(final String controller,
                                 final ReconcileRequest request,
                                 final int attempt,
                                 final Duration delay) {
        System.out.printf("[%s] retry #%d of %s in %d ms%n", controller, attempt, request, delay.toMillis());
    }

    @Override
    public void onInformerSynced(final Class<?> type) {
        System.out.printf("informers of %s synced%n", type.getSimpleName());
    }

    @Override
    public void onLeadershipAcquired(final String identity) {
        System.out.printf("leading as %s%n", identity);
    }

    @Override
    public void onLeadershipLost(final String identity) {
        System.out.printf("leadership lost by %s%n", identity);
    }

    @Override
    public void onLeaderChanged(final String identity) {
        System.out.printf("the leader is %s%n", identity);
    }

    @Override
    public void onOperatorStarted(final Set<String> namespaces,
                                  final List<String> controllers) {
        System.out.printf("operator started: controllers %s for %s%n", controllers,
                namespaces == null ? "all namespaces" : "namespace(s) " + namespaces);
    }

    @Override
    public void onOperatorStopped() {
        System.out.println("operator stopped");
    }

    @Override
    public void onControllerStarted(final String controller,
                                    final int workers) {
        System.out.printf("[%s] started with %d worker(s)%n", controller, workers);
    }

    @Override
    public void onControllerStopped(final String controller) {
        System.out.printf("[%s] stopped%n", controller);
    }

    @Override
    public void onInformerFailed(final Class<?> type,
                                 final boolean retrying) {
        System.out.printf("informer of %s failed%s%n", type.getSimpleName(), retrying ? ", retrying" : " for good");
    }
}
