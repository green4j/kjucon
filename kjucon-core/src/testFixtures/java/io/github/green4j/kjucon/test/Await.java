/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.github.green4j.kjucon.Operator;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits for something asynchronous to happen.
 */
public final class Await {
    /** How long {@link #until(BooleanSupplier, String)} waits. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long STEP_MILLIS = 20;

    private Await() {
    }

    /**
     * @param condition to wait for
     * @param timeout to wait
     * @param what is awaited, for the failure message
     * @throws AssertionError if the condition does not hold in time
     */
    public static void until(final BooleanSupplier condition,
                             final Duration timeout,
                             final String what) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(STEP_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }

    /**
     * {@link #until(BooleanSupplier, Duration, String)} with the {@link #DEFAULT_TIMEOUT}.
     *
     * @param condition to wait for
     * @param what is awaited, for the failure message
     */
    public static void until(final BooleanSupplier condition,
                             final String what) {
        until(condition, DEFAULT_TIMEOUT, what);
    }

    /**
     * Starts an operator and waits until it is ready: informers synced, leading.
     *
     * @param operator to start
     * @param timeout to wait
     * @return the operator
     */
    public static Operator started(final Operator operator,
                                   final Duration timeout) {
        operator.start();
        until(() -> operator.health().isReady(), timeout, "the operator to be ready");
        return operator;
    }

    /**
     * {@link #started(Operator, Duration)} with the {@link #DEFAULT_TIMEOUT}.
     *
     * @param operator to start
     * @return the operator
     */
    public static Operator started(final Operator operator) {
        return started(operator, DEFAULT_TIMEOUT);
    }
}
