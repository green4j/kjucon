/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.green4j.kjucon.util.Metadata;
import io.github.green4j.kjucon.util.Errors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds and removes finalizers.
 * <p>
 * A finalizer list cannot be managed with server-side apply: an applied list is merged, so a finalizer can be
 * added that way but never removed. The list is therefore written whole, on the condition that it is still what
 * it was read as - a JSON patch {@code test} of the list, then its {@code replace}. A concurrent change of the
 * list, a finalizer of somebody else added or removed, fails the patch instead of being overwritten; a
 * concurrent write of anything else - the status, a label - does not concern it and does not fail it.
 * <p>
 * A list which is still empty cannot be tested this way - a JSON patch cannot test that a field is absent - so
 * the first finalizer is added with a merge patch conditional on the resourceVersion instead.
 * <p>
 * {@link #isConcurrentChange(Throwable)} tells a failed condition: the resource is to be read again and the
 * change repeated.
 */
final class Finalizers {
    private static final String PATH = "/metadata/finalizers";

    private Finalizers() {
    }

    /**
     * @param resource to check
     * @param finalizer to look for
     * @return true if the resource has the finalizer
     */
    static boolean has(final HasMetadata resource,
                       final String finalizer) {
        final List<String> finalizers = resource.getMetadata().getFinalizers();
        return finalizers != null && finalizers.contains(finalizer);
    }

    /**
     * @param resource to check
     * @param finalizer to look for
     * @return true if the resource is not being deleted and has no such finalizer yet
     */
    static boolean needsAdding(final HasMetadata resource,
                               final String finalizer) {
        return !Metadata.isBeingDeleted(resource) && !has(resource, finalizer);
    }

    /**
     * @param resource to check
     * @param finalizer to look for
     * @return true if the resource is being deleted and still has the finalizer
     */
    static boolean needsRemoving(final HasMetadata resource,
                                 final String finalizer) {
        return Metadata.isBeingDeleted(resource) && has(resource, finalizer);
    }

    /**
     * Adds the finalizer unless it is already there.
     *
     * @param client to patch with
     * @param resource as it was read
     * @param finalizer to add
     * @param <T> type of the resource
     * @return the patched resource, or the same one if nothing was to be done
     */
    static <T extends HasMetadata> T add(final KubernetesClient client,
                                         final T resource,
                                         final String finalizer) {
        return replace(client, resource, List.of(), finalizer);
    }

    /**
     * Removes the finalizer if it is there.
     *
     * @param client to patch with
     * @param resource as it was read
     * @param finalizer to remove
     * @param <T> type of the resource
     * @return the patched resource, or the same one if nothing was to be done
     */
    static <T extends HasMetadata> T remove(final KubernetesClient client,
                                            final T resource,
                                            final String finalizer) {
        return replace(client, resource, List.of(finalizer), null);
    }

    /**
     * Removes finalizers and adds one, in a single patch.
     *
     * @param client to patch with
     * @param resource as it was read
     * @param toRemove the finalizers to remove, those which are there
     * @param toAdd the finalizer to add unless it is already there, null for none
     * @param <T> type of the resource
     * @return the patched resource, or the same one if nothing was to be done
     */
    static <T extends HasMetadata> T replace(final KubernetesClient client,
                                             final T resource,
                                             final Collection<String> toRemove,
                                             final String toAdd) {
        final List<String> current = resource.getMetadata().getFinalizers();
        final List<String> finalizers = current == null ? new ArrayList<>() : new ArrayList<>(current);
        finalizers.removeAll(toRemove);
        if (toAdd != null && !finalizers.contains(toAdd)) {
            finalizers.add(toAdd);
        }
        if (finalizers.equals(current == null ? List.of() : current)) {
            return resource;
        }
        return patch(client, resource, finalizers);
    }

    /**
     * @param error of {@link #add} or {@link #remove}
     * @return true if it failed because the finalizer list changed since it was read: read it again and repeat
     */
    static boolean isConcurrentChange(final Throwable error) {
        return error instanceof ConcurrentChangeException
                || Errors.isConflict(error); // the resourceVersion condition of a first finalizer
    }

    private static <T extends HasMetadata> T patch(final KubernetesClient client,
                                                   final T resource,
                                                   final List<String> finalizers) {
        final List<String> current = resource.getMetadata().getFinalizers();
        if (current == null || current.isEmpty()) {
            return mergePatch(client, resource, finalizers);
        }
        try {
            return JsonPatch.testAndReplace(client, resource, PATH, current, PATH, finalizers);
        } catch (final KubernetesClientException e) {
            // whether the list changed is only told by reading it again
            if (!JsonPatch.isFailedTest(e)) {
                throw e;
            }
            final T fresh;
            try {
                fresh = client.resource(resource).get();
            } catch (final KubernetesClientException readError) {
                e.addSuppressed(readError);
                throw e;
            }
            if (fresh == null || !current.equals(fresh.getMetadata().getFinalizers())) {
                throw new ConcurrentChangeException(e);
            }
            throw e;
        }
    }

    private static <T extends HasMetadata> T mergePatch(final KubernetesClient client,
                                                        final T resource,
                                                        final List<String> finalizers) {
        final ObjectMeta meta = resource.getMetadata();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("finalizers", finalizers);
        if (meta.getResourceVersion() != null) {
            metadata.put("resourceVersion", meta.getResourceVersion());
        }
        final Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("metadata", metadata);
        final String json = client.getKubernetesSerialization().asJson(patch);
        return client.resource(resource).patch(PatchContext.of(PatchType.JSON_MERGE), json);
    }

    /**
     * The finalizer list changed between the read of a resource and the patch of its finalizers.
     */
    static final class ConcurrentChangeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ConcurrentChangeException(final Throwable cause) {
            super("The finalizers changed since they were read", cause);
        }
    }
}
