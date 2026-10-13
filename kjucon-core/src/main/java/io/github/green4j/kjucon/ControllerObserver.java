/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Told about everything controllers do - the place to hang metrics and logs on. Every method does nothing by
 * default, so an observer implements only what it measures.
 * <p>
 * Calls come from the controllers' own threads, so they must be cheap and must not block. An observer which
 * throws is not able to break anything: the exception goes to the {@link ErrorHandler} and the controller goes
 * on.
 * <p>
 * The {@code controller} argument is the name of the controller, so that one observer given to the
 * {@link Operator} can tell its controllers apart.
 */
public interface ControllerObserver {

    /** Why a request was queued. */
    enum Cause {
        /** The primary resource was added, changed or deleted. */
        PRIMARY_EVENT,
        /** Something the primary resource owns or watches changed. */
        SECONDARY_EVENT,
        /** An {@link EventSource}, or a direct {@link Controller#enqueue(String, String)}. */
        EXTERNAL_EVENT,
        /** The reconcile asked for it: a requeue, or a resync after success. */
        REQUEUE,
        /** The reconcile failed and is retried. */
        RETRY,
        /** The periodic resync of the primary informer. */
        RESYNC
    }

    /** How a reconcile or a cleanup ended. */
    enum Outcome {
        /** Reached the desired state. */
        SUCCESS,
        /** Not there yet, and asked to be repeated. */
        REQUEUE,
        /** Failed. */
        ERROR,
        /** The resource is invalid; not retried with back-off, only on its next event or resync. */
        INVALID
    }

    /**
     * A request was queued - or marked to be queued again once its current processing ends. Not called for a
     * request which was already waiting in the queue. Fires often, so keep it cheap.
     *
     * @param controller name
     * @param request queued
     * @param cause of it
     */
    default void onEnqueued(final String controller,
                            final ReconcileRequest request,
                            final Cause cause) {
    }

    /**
     * A reconcile begins. Always followed by exactly one {@link #onReconcileFinished}.
     *
     * @param controller name
     * @param request reconciled
     */
    default void onReconcileStarted(final String controller,
                                    final ReconcileRequest request) {
    }

    /**
     * A reconcile ended.
     *
     * @param controller name
     * @param request reconciled
     * @param outcome of it
     * @param durationNanos of the reconcile, finalizer handling included
     */
    default void onReconcileFinished(final String controller,
                                     final ReconcileRequest request,
                                     final Outcome outcome,
                                     final long durationNanos) {
    }

    /**
     * A cleanup of a resource being deleted begins. Always followed by exactly one {@link #onCleanupFinished}.
     *
     * @param controller name
     * @param request cleaned up
     */
    default void onCleanupStarted(final String controller,
                                  final ReconcileRequest request) {
    }

    /**
     * A cleanup ended. A {@link Outcome#SUCCESS} is followed by {@link #onFinalizerRemoved}.
     *
     * @param controller name
     * @param request cleaned up
     * @param outcome of it
     * @param durationNanos of the cleanup
     */
    default void onCleanupFinished(final String controller,
                                   final ReconcileRequest request,
                                   final Outcome outcome,
                                   final long durationNanos) {
    }

    /**
     * The controller's finalizer was added to a resource - the first reconcile of it.
     *
     * @param controller name
     * @param request of the resource
     */
    default void onFinalizerAdded(final String controller,
                                  final ReconcileRequest request) {
    }

    /**
     * The controller's finalizer was removed from a resource; the API server can now delete it.
     *
     * @param controller name
     * @param request of the resource
     */
    default void onFinalizerRemoved(final String controller,
                                    final ReconcileRequest request) {
    }

    /**
     * A failed request is scheduled for a retry. The failure itself is reported to the
     * {@link ErrorHandler}.
     *
     * @param controller name
     * @param request to retry
     * @param attempt the number of failures in a row, from 1
     * @param delay before the retry
     */
    default void onRetryScheduled(final String controller,
                                  final ReconcileRequest request,
                                  final int attempt,
                                  final Duration delay) {
    }

    /**
     * The queue changed size. Fires often, so keep it cheap.
     *
     * @param controller name
     * @param depth requests waiting to be taken, not counting the delayed ones
     */
    default void onQueueDepth(final String controller,
                              final int depth) {
    }

    /**
     * The informers of a type have listed everything and are watching. Reconciles do not begin before all the
     * informers of the operator have.
     *
     * @param type of the resources
     */
    default void onInformerSynced(final Class<?> type) {
    }

    /**
     * This instance became the leader and starts its controllers.
     *
     * @param identity of this instance
     */
    default void onLeadershipAcquired(final String identity) {
    }

    /**
     * This instance lost the leadership and stops its controllers. The operator does not take part in the
     * election any more; its health reports it not live, and unless told otherwise the process exits, so that
     * it is restarted.
     *
     * @param identity of this instance
     */
    default void onLeadershipLost(final String identity) {
    }

    /**
     * Some instance - this one or another - is known to lead now.
     *
     * @param identity of the leader
     */
    default void onLeaderChanged(final String identity) {
    }

    /**
     * The operator started: its informers are starting, and its controllers start at once, or once it leads.
     *
     * @param namespaces watched, null for all of them
     * @param controllers names, in the order they were configured
     */
    default void onOperatorStarted(final Set<String> namespaces,
                                   final List<String> controllers) {
    }

    /**
     * The operator stopped: its controllers, its leader election and its informers.
     */
    default void onOperatorStopped() {
    }

    /**
     * A controller started its workers.
     *
     * @param controller name
     * @param workers count
     */
    default void onControllerStarted(final String controller,
                                     final int workers) {
    }

    /**
     * A controller stopped its workers.
     *
     * @param controller name
     */
    default void onControllerStopped(final String controller) {
    }

    /**
     * An informer of a type failed to list or watch. The error itself also goes to the {@link ErrorHandler}.
     *
     * @param type of the resources
     * @param retrying true if the informer keeps trying, false if it gave up - the operator is then not live
     */
    default void onInformerFailed(final Class<?> type,
                                  final boolean retrying) {
    }
}
