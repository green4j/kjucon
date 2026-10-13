/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.status.StandardStatus;


/**
 * Writes the readiness of a composition into the status of its primary resource.
 *
 * @param <P> type of the primary resource
 */
@FunctionalInterface
public interface ComposedStatusUpdater<P extends HasMetadata> {

    /**
     * @param context of the primary resource
     * @param readiness of the composition: {@link Readiness.State#TERMINATING} during the cleanup
     */
    void update(ReconcileContext<P> context,
                Readiness readiness);

    /**
     * The default, for a {@code CustomResource<?, StandardStatus>} (or a subclass of {@link StandardStatus}):
     * Ready, Pending with what is awaited, Error with what failed, Deleting - with the conditions and the
     * generation which go with them. Writes only when something changed. Resources of other status types are
     * left alone.
     *
     * @param <P> type of the primary resource
     * @return the updater
     */
    static <P extends HasMetadata> ComposedStatusUpdater<P> standard() {
        return (context, readiness) -> context.patchStatus(latest -> {
            final StandardStatus status = StandardStatuses.of(latest);
            if (status == null) {
                return false;
            }
            final Long generation = latest.getMetadata().getGeneration();
            switch (readiness.state()) {
                case READY:
                    return status.markReady(generation);
                case FAILED:
                    return status.markError(readiness.message(), generation);
                case TERMINATING:
                    return status.markDeleting(generation);
                default:
                    return status.update(StandardStatus.PENDING, readiness.message(), generation);
            }
        });
    }

    /**
     * @param <P> type of the primary resource
     * @return an updater which writes nothing - for a status managed some other way
     */
    static <P extends HasMetadata> ComposedStatusUpdater<P> none() {
        return (context, readiness) -> {
        };
    }
}
