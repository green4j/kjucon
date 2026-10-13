/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.github.green4j.kjucon.ReconcileRequest;

import java.util.List;

/**
 * Told what a composition does with its objects. Every method does nothing by default. Calls come from the
 * controller's workers, so they must be cheap; what an observer throws goes to the operator's error handler,
 * and the reconcile goes on.
 */
public interface CompositionObserver {

    /**
     * A component was applied.
     *
     * @param primary resource
     * @param component applied
     * @param durationNanos of building and applying it
     */
    default void onComponentApplied(final ReconcileRequest primary,
                                    final Component<?, ?> component,
                                    final long durationNanos) {
    }

    /**
     * A component was not applied: something it depends on is not ready yet.
     *
     * @param primary resource
     * @param component waiting
     * @param blockedBy the components it waits for
     */
    default void onComponentWaiting(final ReconcileRequest primary,
                                    final Component<?, ?> component,
                                    final List<Component<?, ?>> blockedBy) {
    }

    /**
     * The readiness of a component changed since the last reconcile of the resource by this instance.
     *
     * @param primary resource
     * @param component changed
     * @param from previous readiness, null on the first reconcile
     * @param to current readiness
     */
    default void onComponentReadinessChanged(final ReconcileRequest primary,
                                             final Component<?, ?> component,
                                             final Readiness from,
                                             final Readiness to) {
    }

    /**
     * An object which left the composition was deleted.
     *
     * @param primary resource
     * @param object deleted
     */
    default void onPruned(final ReconcileRequest primary,
                          final HasMetadata object) {
    }

    /**
     * An object with {@link DeletePolicy#ORPHAN} was released rather than deleted.
     *
     * @param primary resource
     * @param object released
     */
    default void onOrphaned(final ReconcileRequest primary,
                            final HasMetadata object) {
    }

    /**
     * An object was deleted by the cleanup of the resource.
     *
     * @param primary resource
     * @param object deleted
     */
    default void onDeleted(final ReconcileRequest primary,
                           final HasMetadata object) {
    }

    /**
     * The readiness of the whole composition changed since the last reconcile of the resource by this instance.
     *
     * @param primary resource
     * @param from previous readiness, null on the first reconcile
     * @param to current readiness
     */
    default void onCompositionReadinessChanged(final ReconcileRequest primary,
                                               final Readiness from,
                                               final Readiness to) {
    }
}
