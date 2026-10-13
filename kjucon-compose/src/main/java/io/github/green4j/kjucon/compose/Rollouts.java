/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.github.green4j.kjucon.util.Hashes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stamps the hash of what a workload's pods read on its pod template, so that a change of it rolls the pods - the
 * {@code checksum/config} annotation of Helm charts, without a Reloader.
 */
final class Rollouts {
    private static final int HASH_LENGTH = 32;
    private static final String DATA = "data";

    private Rollouts() {
    }

    static String annotation(final String labelDomain) {
        return labelDomain + "/rollout-hash";
    }

    static void stamp(final HasMetadata workload,
                      final List<HasMetadata> sources,
                      final String labelDomain,
                      final KubernetesSerialization serialization) {
        final PodTemplateSpec template = template(workload);
        if (template.getMetadata() == null) {
            template.setMetadata(new ObjectMeta());
        }
        final ObjectMeta meta = template.getMetadata();
        final Map<String, String> annotations = meta.getAnnotations() == null
                ? new HashMap<>() : new HashMap<>(meta.getAnnotations());
        annotations.put(annotation(labelDomain), hash(sources, serialization));
        meta.setAnnotations(annotations);
    }

    private static PodTemplateSpec template(final HasMetadata workload) {
        if (workload instanceof Deployment && ((Deployment) workload).getSpec() != null) {
            return ensure(((Deployment) workload).getSpec().getTemplate(), workload);
        }
        if (workload instanceof StatefulSet && ((StatefulSet) workload).getSpec() != null) {
            return ensure(((StatefulSet) workload).getSpec().getTemplate(), workload);
        }
        if (workload instanceof DaemonSet && ((DaemonSet) workload).getSpec() != null) {
            return ensure(((DaemonSet) workload).getSpec().getTemplate(), workload);
        }
        throw new IllegalArgumentException("rolloutOn works with a Deployment, StatefulSet or DaemonSet, not "
                + workload.getKind() + ' ' + workload.getMetadata().getName());
    }

    private static PodTemplateSpec ensure(final PodTemplateSpec template,
                                          final HasMetadata workload) {
        if (template == null) {
            throw new IllegalArgumentException(workload.getKind() + ' ' + workload.getMetadata().getName()
                    + " has no pod template");
        }
        return template;
    }

    private static String hash(final List<HasMetadata> sources,
                               final KubernetesSerialization serialization) {
        final List<Object> content = new ArrayList<>(sources.size());
        for (final HasMetadata source : sources) {
            content.add(content(source));
        }
        return Hashes.sha256(serialization.asJson(content), HASH_LENGTH);
    }

    // the data alone, in a stable order: metadata changes must not roll pods
    private static Object content(final HasMetadata source) {
        final Map<String, Object> content = new TreeMap<>();
        content.put("kind", source.getKind());
        content.put("name", source.getMetadata().getName());
        if (source instanceof ConfigMap) {
            content.put(DATA, sorted(((ConfigMap) source).getData()));
            content.put("binaryData", sorted(((ConfigMap) source).getBinaryData()));
        } else if (source instanceof Secret) {
            content.put(DATA, sorted(((Secret) source).getData()));
            content.put("stringData", sorted(((Secret) source).getStringData()));
        } else {
            content.put("object", source);
        }
        return content;
    }

    private static Map<String, String> sorted(final Map<String, String> map) {
        return map == null ? null : new TreeMap<>(map);
    }
}
