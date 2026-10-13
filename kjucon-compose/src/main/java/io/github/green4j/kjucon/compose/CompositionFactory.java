/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.github.green4j.kjucon.ReconcileContext;

/**
 * Builds the composition of a primary resource from its current state - on every reconcile, so that a composition
 * can follow the spec: a component which is not needed is simply not added, and the reconciler prunes its object.
 *
 * @param <P> type of the primary resource
 */
@FunctionalInterface
public interface CompositionFactory<P extends HasMetadata> {

    /**
     * @param context of the primary resource
     * @return its composition
     * @throws io.github.green4j.kjucon.InvalidResourceException if the resource cannot be composed
     */
    Composition<P> create(ReconcileContext<P> context);
}
