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
 * Adds to the metadata of every object of a composition before its factory builds it - labels or annotations a
 * whole composition shares, such as a tenant or a trading day taken from the primary resource.
 *
 * @param <P> type of the primary resource
 */
@FunctionalInterface
public interface MetadataDecorator<P extends HasMetadata> {

    /**
     * @param context of the primary resource
     * @param component the metadata is for
     * @param metadata to add to
     */
    void decorate(ReconcileContext<P> context,
                  Component<P, ?> component,
                  ObjectMeta metadata);
}
