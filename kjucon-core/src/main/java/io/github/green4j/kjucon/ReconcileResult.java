/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Duration;
import java.util.Objects;

/**
 * How a reconcile ended, and what the controller is to do next.
 * <ul>
 *   <li>{@link #success()}: done; nothing is scheduled. The next event about the resource, or about anything it
 *   owns, reconciles it again.</li>
 *   <li>{@link #success(Duration)}: done, but look again after the period anyway - for a state which changes
 *   outside Kubernetes and cannot be watched.</li>
 *   <li>{@link #requeue()}, {@link #requeueAfter(Duration)}: not done yet - waiting for something which does not
 *   send events. Not counted as a failure.</li>
 *   <li>{@link #error(Throwable)}: failed. Retried with the controller's back-off, and reported to its error
 *   handler. Throwing from the reconciler means the same.</li>
 * </ul>
 * For a cleanup, only a success lets the finalizer go.
 */
public final class ReconcileResult {
    private static final ReconcileResult SUCCESS = new ReconcileResult(true, null, null);
    private static final ReconcileResult REQUEUE = new ReconcileResult(false, Duration.ZERO, null);

    private final boolean success;
    private final Duration after;
    private final Throwable error;

    private ReconcileResult(final boolean success,
                            final Duration after,
                            final Throwable error) {
        this.success = success;
        this.after = after;
        this.error = error;
    }

    /**
     * @return the result of a reconcile which reached the desired state
     */
    public static ReconcileResult success() {
        return SUCCESS;
    }

    /**
     * @param resyncAfter to reconcile again after anyway
     * @return the result of a reconcile which reached the desired state, for now
     */
    public static ReconcileResult success(final Duration resyncAfter) {
        return new ReconcileResult(true, positive(resyncAfter), null);
    }

    /**
     * @return the result of a reconcile which is to be repeated as soon as possible
     */
    public static ReconcileResult requeue() {
        return REQUEUE;
    }

    /**
     * @param delay to repeat after
     * @return the result of a reconcile which is to be repeated after the delay
     */
    public static ReconcileResult requeueAfter(final Duration delay) {
        return new ReconcileResult(false, positive(delay), null);
    }

    /**
     * @param error which failed the reconcile
     * @return the result of a failed reconcile
     */
    public static ReconcileResult error(final Throwable error) {
        return new ReconcileResult(false, null, Objects.requireNonNull(error, "error"));
    }

    /**
     * @return true for a success, with or without a resync
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return true for a failure
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * @return the error of a failure, otherwise null
     */
    public Throwable error() {
        return error;
    }

    /**
     * @return when to reconcile again - zero for as soon as possible - or null if nothing is scheduled
     */
    public Duration after() {
        return after;
    }

    private static Duration positive(final Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isNegative()) {
            throw new IllegalArgumentException("Negative duration: " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        if (error != null) {
            return "error(" + error + ')';
        }
        if (success) {
            return after == null ? "success" : "success(resync after " + after + ')';
        }
        return Duration.ZERO.equals(after) ? "requeue" : "requeueAfter(" + after + ')';
    }
}
