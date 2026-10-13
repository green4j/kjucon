/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.github.green4j.kjucon.util.Errors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Server-side apply. A controller applies the desired state of what it owns under its own field manager: the
 * API server merges it with what other managers own, keeps their fields (labels, annotations, data keys added
 * by others) and drops the fields this manager stopped applying.
 * <p>
 * Conflicts are always forced. A controller is the authority over what it applies, and a conflict left
 * unforced would only stop the reconcile on every pass until somebody resolved it by hand.
 */
final class ServerSideApply {
    private static final String STATUS = "status";
    private static final String MANAGER = "manager";
    private static final String FIELDS = "fieldsV1";

    private ServerSideApply() {
    }

    /**
     * Applies the desired state of an object.
     *
     * @param client to apply with
     * @param fieldManager owning what is applied
     * @param desired object, as built for this apply: no resourceVersion, no managedFields
     * @param <T> type of the object
     * @return the object as the API server returned it
     */
    static <T extends HasMetadata> T apply(final KubernetesClient client,
                                           final String fieldManager,
                                           final T desired) {
        return withoutManagedFields(desired, () -> client.resource(desired).patch(context(fieldManager)));
    }

    /**
     * Applies the status of an object through its status subresource. Only the status is sent, with what
     * identifies the object: an apply makes its field manager the owner of every field it carries, and the
     * spec, labels and annotations of the object are not the status writer's to own.
     *
     * @param client to apply with
     * @param fieldManager owning the status
     * @param resource carrying the status to apply
     * @param <T> type of the object
     * @return the object as the API server returned it
     */
    @SuppressWarnings("unchecked")
    static <T extends HasMetadata> T applyStatus(final KubernetesClient client,
                                                 final String fieldManager,
                                                 final T resource) {
        final KubernetesSerialization serialization = client.getKubernetesSerialization();
        final Map<String, Object> full = serialization.convertValue(resource, Map.class);
        final ObjectMeta meta = resource.getMetadata();
        final GenericKubernetesResource statusOnly = new GenericKubernetesResource();
        statusOnly.setApiVersion(resource.getApiVersion());
        statusOnly.setKind(resource.getKind());
        statusOnly.setMetadata(new ObjectMetaBuilder()
                .withName(meta.getName())
                .withNamespace(meta.getNamespace())
                .build());
        statusOnly.setAdditionalProperty(STATUS, full.get(STATUS));
        final ResourceDefinitionContext definition = ResourceDefinitionContext.fromResourceType(resource.getClass());
        final NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> resources = meta.getNamespace() == null
                ? client.genericKubernetesResources(definition)
                : client.genericKubernetesResources(definition).inNamespace(meta.getNamespace());
        final GenericKubernetesResource applied = resources.resource(statusOnly)
                .subresource(STATUS)
                .patch(context(fieldManager));
        return (T) serialization.convertValue(applied, resource.getClass());
    }

    /**
     * Hands the fields of former field managers over to a manager, like {@code csaupgrade} of client-go does: an
     * entry of a former manager is merged into the entry of the manager for the same operation and subresource,
     * or renamed to the manager if there is none. The manager then owns what they owned, and what it no longer
     * applies is removed by its next apply - for a controller which was renamed, whose former name would keep
     * those fields forever otherwise.
     * <p>
     * The patch tests the resourceVersion the object was read with. If the object changed meanwhile, nothing is
     * handed over and the object is returned as given: the next apply tries again.
     *
     * @param client to patch with
     * @param object as an apply returned it, with its managedFields
     * @param formerManagers whose fields to hand over
     * @param manager to hand them to
     * @param <T> type of the object
     * @return the object as the patch returned it, or as given if there was nothing to hand over or it changed
     */
    @SuppressWarnings("unchecked")
    static <T extends HasMetadata> T takeOverManagers(final KubernetesClient client,
                                                      final T object,
                                                      final Collection<String> formerManagers,
                                                      final String manager) {
        final ObjectMeta meta = object.getMetadata();
        final List<ManagedFieldsEntry> entries = meta.getManagedFields();
        if (entries == null || meta.getResourceVersion() == null
                || entries.stream().noneMatch(e -> formerManagers.contains(e.getManager()))) {
            return object;
        }
        final KubernetesSerialization serialization = client.getKubernetesSerialization();
        final List<Map<String, Object>> taken = new ArrayList<>(entries.size());
        for (final Object entry : serialization.convertValue(entries, List.class)) {
            taken.add((Map<String, Object>) entry);
        }
        final List<Map<String, Object>> former = new ArrayList<>();
        taken.removeIf(entry -> formerManagers.contains(entry.get(MANAGER)) && former.add(entry));
        for (final Map<String, Object> entry : former) {
            final Map<String, Object> current = find(taken, manager, entry);
            if (current == null) {
                entry.put(MANAGER, manager);
                taken.add(entry);
            } else {
                current.put(FIELDS, merge((Map<String, Object>) current.get(FIELDS),
                        (Map<String, Object>) entry.get(FIELDS)));
            }
        }
        try {
            return JsonPatch.testAndReplace(client, object, "/metadata/resourceVersion", meta.getResourceVersion(),
                    "/metadata/managedFields", taken);
        } catch (final KubernetesClientException e) {
            if (Errors.isConflict(e) || JsonPatch.isFailedTest(e)) {
                return object; // changed meanwhile: a failed test
            }
            throw e;
        }
    }

    private static Map<String, Object> find(final List<Map<String, Object>> entries,
                                            final String manager,
                                            final Map<String, Object> like) {
        for (final Map<String, Object> entry : entries) {
            if (manager.equals(entry.get(MANAGER))
                    && Objects.equals(entry.get("operation"), like.get("operation"))
                    && Objects.equals(entry.get("subresource"), like.get("subresource"))) {
                return entry;
            }
        }
        return null;
    }

    // the union of two fieldsV1 sets: trees of "f:<field>", "k:<key>", "v:<value>" and "." entries
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(final Map<String, Object> into,
                                             final Map<String, Object> from) {
        final Map<String, Object> merged = into == null ? new LinkedHashMap<>() : new LinkedHashMap<>(into);
        if (from == null) {
            return merged;
        }
        for (final Map.Entry<String, Object> field : from.entrySet()) {
            final Object present = merged.get(field.getKey());
            if (present instanceof Map && field.getValue() instanceof Map) {
                merged.put(field.getKey(),
                        merge((Map<String, Object>) present, (Map<String, Object>) field.getValue()));
            } else if (present == null) {
                merged.put(field.getKey(), field.getValue());
            }
        }
        return merged;
    }

    private static PatchContext context(final String fieldManager) {
        return new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager(fieldManager)
                .withForce(true)
                .build();
    }

    // An apply must not carry managedFields: the server would take them as the new ownership record.
    private static <T extends HasMetadata> T withoutManagedFields(final HasMetadata resource,
                                                                  final Supplier<T> apply) {
        final ObjectMeta meta = resource.getMetadata();
        final List<ManagedFieldsEntry> managedFields = meta.getManagedFields();
        if (managedFields == null) {
            return apply.get();
        }
        meta.setManagedFields(null);
        try {
            return apply.get();
        } finally {
            meta.setManagedFields(managedFields);
        }
    }
}
