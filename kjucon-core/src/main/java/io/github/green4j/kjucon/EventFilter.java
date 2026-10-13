/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

/**
 * Decides which updates of a primary resource are worth a reconcile.
 * <p>
 * Whatever the filter says, a controller always reconciles a resource which is being deleted, and one which needs
 * its finalizer added or removed; additions and deletions always pass. Updates of the status alone do not change
 * the generation, so the default filter lets a controller write the status of its own resource without waking
 * itself up - for a type which has a generation; any change of one which has none counts.
 *
 * @param <P> type of the primary resource
 */
@FunctionalInterface
public interface EventFilter<P extends HasMetadata> {

    /**
     * @param oldResource before the update
     * @param newResource after the update
     * @return true if the update is to be reconciled
     */
    boolean accept(P oldResource,
                   P newResource);

    /**
     * @param other filter
     * @return a filter accepting what either accepts
     */
    default EventFilter<P> or(final EventFilter<P> other) {
        return (o, n) -> accept(o, n) || other.accept(o, n);
    }

    /**
     * @param other filter
     * @return a filter accepting what both accept
     */
    default EventFilter<P> and(final EventFilter<P> other) {
        return (o, n) -> accept(o, n) && other.accept(o, n);
    }

    /**
     * The default: a change of the spec (the generation), of the labels or of the annotations. Annotations are
     * in because operators are commonly configured through them, and a change of them does not move the
     * generation.
     *
     * @param <P> type of the primary resource
     * @return the filter
     */
    static <P extends HasMetadata> EventFilter<P> defaults() {
        return EventFilter.<P>generationChanged()
                .or(labelsChanged())
                .or(annotationsChanged());
    }

    /**
     * @param <P> type of the primary resource
     * @return a filter accepting every update, the status-only ones included
     */
    static <P extends HasMetadata> EventFilter<P> all() {
        return (o, n) -> true;
    }

    /**
     * A change of the generation - of the spec. An object of a type which has no generation, such as a ConfigMap
     * or a Secret, cannot tell a change of its content from any other: every change of it - a new resource
     * version - is accepted.
     *
     * @param <P> type of the primary resource
     * @return a filter accepting a change of the generation
     */
    static <P extends HasMetadata> EventFilter<P> generationChanged() {
        return (o, n) -> n.getMetadata().getGeneration() == null
                ? !Objects.equals(o.getMetadata().getResourceVersion(), n.getMetadata().getResourceVersion())
                : !Objects.equals(o.getMetadata().getGeneration(), n.getMetadata().getGeneration());
    }

    /**
     * @param <P> type of the primary resource
     * @return a filter accepting a change of the labels
     */
    static <P extends HasMetadata> EventFilter<P> labelsChanged() {
        return (o, n) -> !Objects.equals(o.getMetadata().getLabels(), n.getMetadata().getLabels());
    }

    /**
     * @param <P> type of the primary resource
     * @return a filter accepting a change of the annotations
     */
    static <P extends HasMetadata> EventFilter<P> annotationsChanged() {
        return (o, n) -> !Objects.equals(o.getMetadata().getAnnotations(), n.getMetadata().getAnnotations());
    }
}
