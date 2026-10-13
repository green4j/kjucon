/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.github.green4j.kjucon.ReconcileContext;

/**
 * Builds the desired state of a component's object from the primary resource.
 *
 * @param <P> type of the primary resource
 * @param <R> type of the object
 */
@FunctionalInterface
public interface ComponentFactory<P extends HasMetadata, R extends HasMetadata> {

    /**
     * @param context of the primary resource
     * @param metadata to give the object: name, namespace, owner reference, labels and annotations are already
     *                 set; add to them rather than replace them
     * @return the desired object
     */
    R desired(ReconcileContext<P> context,
              ObjectMeta metadata);
}
