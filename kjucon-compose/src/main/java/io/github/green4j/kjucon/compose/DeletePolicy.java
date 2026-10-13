/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

/**
 * What happens to a component's object when it leaves the composition, or the primary resource is deleted.
 */
public enum DeletePolicy {
    /** It is deleted. */
    DELETE,
    /**
     * It is left in the cluster, released: the owner reference, and the labels and annotations kjucon set, are
     * removed - those another tool set are kept - so that neither the garbage collector nor the composition
     * touches it again - for data which must outlive its owner. Needs {@link CleanupMode#ORDERED}: under
     * {@link CleanupMode#GARBAGE_COLLECTOR} the garbage collector would delete the object with its owner, so a
     * composition with such a component is rejected.
     */
    ORPHAN
}
