/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.OwnerReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps an event about a secondary resource - one a controller owns or watches - to the primary resources to
 * reconcile because of it.
 *
 * @param <S> type of the secondary resource
 */
@FunctionalInterface
public interface SecondaryMapper<S extends HasMetadata> {

    /**
     * @param secondary which was added, changed or deleted
     * @return the primary resources to reconcile; empty if none
     */
    Collection<ReconcileRequest> map(S secondary);

    /**
     * Maps to the owners of the type, as the owner references of the secondary resource name them - what
     * controller-runtime's {@code Owns()} does. An owner reference can only point to an owner in the same
     * namespace, or to a cluster-scoped one.
     *
     * @param ownerType type of the primary resource
     * @param <S> type of the secondary resource
     * @return the mapper
     */
    static <S extends HasMetadata> SecondaryMapper<S> byOwnerReference(final Class<? extends HasMetadata> ownerType) {
        final String kind = HasMetadata.getKind(ownerType);
        final String group = Objects.requireNonNullElse(HasMetadata.getGroup(ownerType), "");
        final boolean namespacedOwner = Namespaced.class.isAssignableFrom(ownerType);
        return secondary -> {
            final List<OwnerReference> references = secondary.getMetadata().getOwnerReferences();
            if (references == null || references.isEmpty()) {
                return List.of();
            }
            final List<ReconcileRequest> result = new ArrayList<>(1);
            for (final OwnerReference reference : references) {
                if (kind.equals(reference.getKind()) && Objects.equals(group, groupOf(reference.getApiVersion()))) {
                    result.add(new ReconcileRequest(
                            namespacedOwner ? secondary.getMetadata().getNamespace() : null,
                            reference.getName()));
                }
            }
            return result;
        };
    }

    /**
     * Maps to the owner kjucon recorded in the annotations of an object it created where an owner reference
     * could not point from: another namespace, cluster scope.
     *
     * @param domain of the operator, see {@link Labels}
     * @param ownerType type of the primary resource
     * @param <S> type of the secondary resource
     * @return the mapper
     */
    static <S extends HasMetadata> SecondaryMapper<S> byOwnerAnnotation(final String domain,
                                                                        final Class<? extends HasMetadata> ownerType) {
        final String kindAnnotation = Labels.ownerKind(domain);
        final String ownerAnnotation = Labels.owner(domain);
        final String expectedKind = Labels.kindOf(ownerType);
        return secondary -> {
            final Map<String, String> annotations = secondary.getMetadata().getAnnotations();
            if (annotations == null || !expectedKind.equals(annotations.get(kindAnnotation))) {
                return List.of();
            }
            final String owner = annotations.get(ownerAnnotation);
            if (owner == null || owner.isEmpty()) {
                return List.of();
            }
            final int slash = owner.indexOf('/');
            return List.of(slash < 0
                    ? new ReconcileRequest(null, owner)
                    : new ReconcileRequest(owner.substring(0, slash), owner.substring(slash + 1)));
        };
    }

    /**
     * Maps through both the owner references and the owner annotations - what {@link ControllerBuilder#owns}
     * uses.
     *
     * @param domain of the operator
     * @param ownerType type of the primary resource
     * @param <S> type of the secondary resource
     * @return the mapper
     */
    static <S extends HasMetadata> SecondaryMapper<S> byOwner(final String domain,
                                                              final Class<? extends HasMetadata> ownerType) {
        final SecondaryMapper<S> byReference = byOwnerReference(ownerType);
        final SecondaryMapper<S> byAnnotation = byOwnerAnnotation(domain, ownerType);
        return secondary -> {
            final Collection<ReconcileRequest> referenced = byReference.map(secondary);
            final Collection<ReconcileRequest> annotated = byAnnotation.map(secondary);
            if (annotated.isEmpty()) {
                return referenced;
            }
            if (referenced.isEmpty()) {
                return annotated;
            }
            final List<ReconcileRequest> result = new ArrayList<>(referenced);
            for (final ReconcileRequest request : annotated) {
                if (!result.contains(request)) {
                    result.add(request);
                }
            }
            return result;
        };
    }

    private static String groupOf(final String apiVersion) {
        if (apiVersion == null) {
            return null;
        }
        final int slash = apiVersion.indexOf('/');
        return slash < 0 ? "" : apiVersion.substring(0, slash);
    }
}
