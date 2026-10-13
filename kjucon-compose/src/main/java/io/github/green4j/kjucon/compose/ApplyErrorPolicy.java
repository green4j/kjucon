/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

/**
 * What happens to the reconcile of a composition when the API server refuses to apply a component's object - an
 * invalid object, missing permissions, a server error. Either way the error goes to the error handler and the
 * resource is reconciled again with back-off.
 */
public enum ApplyErrorPolicy {
    /**
     * The reconcile stops at the component: the components after it are not applied, and nothing is pruned until
     * a reconcile gets through. Nothing changes on top of a composition applied in part. The default.
     */
    STOP_COMPOSITION,
    /**
     * The component fails alone, with the error of the server: the components which depend on it wait, the others
     * are applied, and what left the composition is pruned - for a component the rest can do without.
     */
    FAIL_COMPONENT
}
