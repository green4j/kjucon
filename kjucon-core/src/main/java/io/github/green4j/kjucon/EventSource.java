/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

/**
 * Wakes a controller up on changes Kubernetes does not know about: a message, a callback of an external system,
 * a state which the controller would otherwise have to poll. A source is started with the controller, once it is
 * leading, and closed with it.
 */
public interface EventSource extends AutoCloseable {

    /** Queues requests to the controller the source belongs to. Thread-safe. */
    @FunctionalInterface
    interface Enqueuer {
        /**
         * @param request to reconcile
         */
        void enqueue(ReconcileRequest request);
    }

    /**
     * Starts the source.
     *
     * @param enqueuer to queue requests with, valid until {@link #close()}
     */
    void start(Enqueuer enqueuer);

    /**
     * Stops the source. The default does nothing.
     */
    @Override
    default void close() {
    }
}
