/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * The logic of a controller: brings the cluster to what a primary resource describes.
 * <p>
 * A reconcile is level-based: it is given the current state, not the event which caused it, and must work out
 * everything from that - several events may have been collapsed into one call, and a call may come with nothing
 * changed at all. Calls for one resource never overlap; calls for different resources run on the controller's
 * workers in parallel.
 *
 * @param <P> type of the primary resource
 */
public interface Reconciler<P extends HasMetadata> extends AutoCloseable {

    /**
     * Configures the controller this reconciler is given to - the secondary types it owns, its finalizer - when
     * {@link Operator.Builder#controller} adds it, before the caller configures it further. The default does
     * nothing.
     *
     * @param controller the builder of the controller
     */
    default void configure(final ControllerBuilder<P> controller) {
    }

    /**
     * Brings the cluster to the state the resource describes.
     *
     * @param context of the resource, valid for the duration of this call only
     * @return how it ended; throwing means {@link ReconcileResult#error(Throwable)}
     */
    ReconcileResult reconcile(ReconcileContext<P> context);

    /**
     * Undoes what the resource caused, when it is being deleted. Called only while the controller's finalizer
     * holds the resource, and until it returns a success - only then the finalizer is removed.
     *
     * @param context of the resource, valid for the duration of this call only
     * @return how it ended; throwing means {@link ReconcileResult#error(Throwable)}
     */
    default ReconcileResult cleanup(final ReconcileContext<P> context) {
        return ReconcileResult.success();
    }

    /**
     * The resource is gone from the cluster: whatever the reconciler keeps about it can go too. Called from the
     * informer's thread, once per deletion, without a context; must not block.
     *
     * @param request of the resource which is gone
     */
    default void deleted(final ReconcileRequest request) {
    }

    /**
     * Releases what the reconciler holds, when the operator it runs in is closed - after its controllers have
     * stopped. The default does nothing.
     */
    @Override
    default void close() {
    }
}
