/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

/**
 * Whose an object is, as its labels tell it to a controller reconciling a resource: see
 * {@link ReconcileContext#ownershipOf}.
 */
public enum Ownership {
    /** The resource's, applied by this controller under its name or a former one. */
    OURS,
    /** The resource's, applied by another controller composing the same resource. */
    OTHER_CONTROLLER,
    /** Another resource's. */
    OTHER_RESOURCE,
    /** Nobody's: no owner, or no controller kjucon would know. */
    UNOWNED
}
