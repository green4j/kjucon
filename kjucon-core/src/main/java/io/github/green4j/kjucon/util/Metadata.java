/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Object metadata helpers.
 */
public final class Metadata {

    private Metadata() {
    }

    /**
     * Adds labels to the metadata, replacing those of the same keys.
     *
     * @param meta to change
     * @param labels to add
     * @return the same metadata
     */
    public static ObjectMeta addLabels(final ObjectMeta meta,
                                       final Map<String, String> labels) {
        meta.setLabels(merge(meta.getLabels(), labels));
        return meta;
    }

    /**
     * Adds annotations to the metadata, replacing those of the same keys.
     *
     * @param meta to change
     * @param annotations to add
     * @return the same metadata
     */
    public static ObjectMeta addAnnotations(final ObjectMeta meta,
                                            final Map<String, String> annotations) {
        meta.setAnnotations(merge(meta.getAnnotations(), annotations));
        return meta;
    }

    /**
     * @param resource to check
     * @return true if the resource has a deletion timestamp
     */
    public static boolean isBeingDeleted(final HasMetadata resource) {
        return resource.getMetadata().getDeletionTimestamp() != null;
    }

    /**
     * @param resource to check
     * @param labels which must all be present with equal values
     * @return true if the resource carries all the labels
     */
    public static boolean hasLabels(final HasMetadata resource,
                                    final Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return true;
        }
        final Map<String, String> actual = resource.getMetadata().getLabels();
        if (actual == null) {
            return false;
        }
        for (final Map.Entry<String, String> e : labels.entrySet()) {
            if (!Objects.equals(e.getValue(), actual.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> merge(final Map<String, String> current,
                                             final Map<String, String> added) {
        final Map<String, String> result = current == null ? new HashMap<>() : new HashMap<>(current);
        if (added != null) {
            result.putAll(added);
        }
        return result;
    }
}
