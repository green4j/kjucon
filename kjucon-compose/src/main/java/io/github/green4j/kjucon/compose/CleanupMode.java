/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

/**
 * How the objects of a composition go away with its primary resource.
 */
public enum CleanupMode {
    /**
     * The controller holds the resource with a finalizer and deletes its objects in reverse dependency order -
     * an object only once everything which depends on it is gone - waiting for each to disappear. The resource
     * shows the Deleting phase meanwhile. The default; the only way for objects outside the namespace of the
     * resource, which no owner reference can tie to it.
     */
    ORDERED,
    /**
     * No finalizer: the garbage collector deletes the objects by their owner references, in no particular order.
     * Simpler and faster, for compositions whose objects all live in the namespace of the resource and need no
     * order. A composition with an object elsewhere, or one with {@link DeletePolicy#ORPHAN}, is rejected: the
     * garbage collector would leak the first and delete the second.
     */
    GARBAGE_COLLECTOR
}
