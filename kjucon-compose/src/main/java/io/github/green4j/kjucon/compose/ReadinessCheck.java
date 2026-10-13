/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Tells how ready an object is.
 *
 * @param <R> type of the object
 */
@FunctionalInterface
public interface ReadinessCheck<R extends HasMetadata> {

    /**
     * @param resource as the API server or the cache has it; never null
     * @return its readiness
     */
    Readiness check(R resource);
}
