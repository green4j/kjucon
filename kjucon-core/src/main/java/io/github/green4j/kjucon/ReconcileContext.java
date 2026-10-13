/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.github.green4j.kjucon.util.Metadata;
import io.github.green4j.kjucon.util.Names;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Everything a reconcile of one primary resource works with: the resource, reads served from the operator's
 * informers, writes under the controller's field manager, and the metadata which ties what it creates to the
 * resource.
 * <p>
 * Reads of a type or namespace the operator informs come from the cache, without a call to the API server; any
 * other read goes to the API server. A context is valid for the duration of the call it is given to only.
 *
 * @param <P> type of the primary resource
 */
public final class ReconcileContext<P extends HasMetadata> {
    private final KubernetesClient client;
    private final InformerRegistry informers;
    private final Observed observed;
    private final String labelDomain;
    private final String controllerName;
    private final List<String> formerNames;
    private final Set<String> siblingNames;
    private final boolean managedByLabel;
    private final ReconcileRequest request;
    private final Class<P> primaryType;
    private final int attempt;

    private P primary;

    ReconcileContext(final KubernetesClient client,
                     final InformerRegistry informers,
                     final Observed observed,
                     final String labelDomain,
                     final String controllerName,
                     final List<String> formerNames,
                     final Set<String> siblingNames,
                     final boolean managedByLabel,
                     final ReconcileRequest request,
                     final Class<P> primaryType,
                     final P primary,
                     final int attempt) {
        this.client = client;
        this.informers = informers;
        this.observed = observed;
        this.labelDomain = labelDomain;
        this.controllerName = controllerName;
        this.formerNames = formerNames;
        this.siblingNames = siblingNames;
        this.managedByLabel = managedByLabel;
        this.request = request;
        this.primaryType = primaryType;
        this.primary = primary;
        this.attempt = attempt;
    }

    /**
     * @return the primary resource, as the cache had it when the reconcile began - or as a status patch through
     *         this context returned it since
     */
    public P primary() {
        return primary;
    }

    /**
     * @return the request being reconciled
     */
    public ReconcileRequest request() {
        return request;
    }

    /**
     * @return the client
     */
    public KubernetesClient client() {
        return client;
    }

    /**
     * @return the name of the controller - also its field manager and the value of its controller label
     */
    public String controllerName() {
        return controllerName;
    }

    /**
     * @return the names the controller had before, whose objects are its own as well
     */
    public List<String> formerControllerNames() {
        return formerNames;
    }

    /**
     * Whose an object is, by its labels: the owner-uid label names the resource, the controller label the
     * controller. An object applied before kjucon set the controller label is told by
     * {@code app.kubernetes.io/managed-by} instead - but only if that names this controller or another of the
     * operator: another tool, such as Helm, may have set it since. An object without an owner-uid label, or with
     * neither controller label it can trust, is nobody's - adopted on an apply, never pruned.
     *
     * @param object to tell
     * @return the ownership
     */
    public Ownership ownershipOf(final HasMetadata object) {
        final Map<String, String> labels = object.getMetadata().getLabels();
        if (labels == null) {
            return Ownership.UNOWNED;
        }
        final String ownerUid = labels.get(Labels.ownerUid(labelDomain));
        if (ownerUid == null) {
            return Ownership.UNOWNED;
        }
        if (!ownerUid.equals(primary.getMetadata().getUid())) {
            return Ownership.OTHER_RESOURCE;
        }
        final String controller = labels.get(Labels.controller(labelDomain));
        if (controller != null) {
            return isOwnName(controller) ? Ownership.OURS : Ownership.OTHER_CONTROLLER;
        }
        final String managedBy = labels.get(Labels.MANAGED_BY);
        if (isOwnName(managedBy)) {
            return Ownership.OURS;
        }
        return managedBy != null && siblingNames.contains(managedBy) ? Ownership.OTHER_CONTROLLER : Ownership.UNOWNED;
    }

    /**
     * @param controller name, may be null
     * @return true if it is the name of this controller, or one it had before
     */
    public boolean isOwnName(final String controller) {
        return controller != null && (controllerName.equals(controller) || formerNames.contains(controller));
    }

    /**
     * @return the domain of the operator's labels, annotations and finalizers
     */
    public String labelDomain() {
        return labelDomain;
    }

    /**
     * @return the namespace of the primary resource, null if it is cluster-scoped
     */
    public String namespace() {
        return request.namespace();
    }

    /**
     * @return the failures in a row before this reconcile, 0 if the last one did not fail
     */
    public int failedAttempts() {
        return attempt;
    }

    /**
     * Tells a failure which was dealt with, but which somebody may want to know about, to the operator's
     * {@link ErrorHandler} - for a reconciler, which has no log of its own.
     *
     * @param message what was being done
     * @param error the failure, may be null
     */
    public void reportError(final String message,
                            final Throwable error) {
        observed.error(controllerName, message + " (" + request + ")", error);
    }

    /**
     * @param type of objects
     * @param namespace of objects, null for a cluster-scoped type
     * @return true if the operator caches the objects of the type in the namespace: reads come from the cache,
     *         and their events reach the controllers which own or watch the type
     */
    public boolean isInformed(final Class<? extends HasMetadata> type,
                              final String namespace) {
        return informers.isInformed(type, namespaceFor(type, namespace));
    }

    /**
     * Reads an object in the namespace of the primary resource.
     *
     * @param type of the object
     * @param name of the object
     * @param <S> the type
     * @return the object, or null if there is none
     */
    public <S extends HasMetadata> S get(final Class<S> type,
                                         final String name) {
        return get(type, namespaceFor(type, namespace()), name);
    }

    /**
     * Reads an object.
     *
     * @param type of the object
     * @param namespace of the object, null if it is cluster-scoped
     * @param name of the object
     * @param <S> the type
     * @return the object, or null if there is none
     */
    public <S extends HasMetadata> S get(final Class<S> type,
                                         final String namespace,
                                         final String name) {
        if (informers.isInformed(type, namespace)) {
            return informers.get(type, namespace, name);
        }
        return getLive(type, namespace, name);
    }

    /**
     * Reads the values of a Secret, decoded: from the cache if the operator watches Secrets there, from the API
     * server otherwise - a Secret a resource only refers to need not be cached at all.
     *
     * @param namespace of the Secret
     * @param name of the Secret
     * @return its values - {@code data} decoded, {@code stringData} as it is - or null if there is no such Secret
     */
    public Map<String, String> secretValues(final String namespace,
                                            final String name) {
        final Secret secret = get(Secret.class, namespace, name);
        if (secret == null) {
            return null;
        }
        final Map<String, String> values = new HashMap<>();
        if (secret.getData() != null) {
            for (final Map.Entry<String, String> e : secret.getData().entrySet()) {
                values.put(e.getKey(), e.getValue() == null ? null
                        : new String(Base64.getDecoder().decode(e.getValue()), StandardCharsets.UTF_8));
            }
        }
        if (secret.getStringData() != null) {
            values.putAll(secret.getStringData());
        }
        return values;
    }

    /**
     * Reads an object from the API server, bypassing the cache.
     *
     * @param type of the object
     * @param namespace of the object, null if it is cluster-scoped
     * @param name of the object
     * @param <S> the type
     * @return the object, or null if there is none
     */
    public <S extends HasMetadata> S getLive(final Class<S> type,
                                             final String namespace,
                                             final String name) {
        return resources(type, namespace).withName(name).get();
    }

    /**
     * Lists objects in the namespace of the primary resource.
     *
     * @param type of the objects
     * @param labels the objects must all carry, may be empty
     * @param <S> the type
     * @return the objects
     */
    public <S extends HasMetadata> List<S> list(final Class<S> type,
                                                final Map<String, String> labels) {
        return list(type, namespaceFor(type, namespace()), labels);
    }

    /**
     * Lists objects.
     *
     * @param type of the objects
     * @param namespace of the objects, null if the type is cluster-scoped
     * @param labels the objects must all carry, may be empty
     * @param <S> the type
     * @return the objects
     */
    public <S extends HasMetadata> List<S> list(final Class<S> type,
                                                final String namespace,
                                                final Map<String, String> labels) {
        if (informers.isInformed(type, namespace)) {
            final List<S> result = new ArrayList<>();
            for (final S item : informers.list(type, namespace)) {
                if (Metadata.hasLabels(item, labels)) {
                    result.add(item);
                }
            }
            return result;
        }
        final MixedOperation<S, KubernetesResourceList<S>, Resource<S>> operation = client.resources(type);
        final KubernetesResourceList<S> list = namespace == null
                ? operation.withLabels(labels).list()
                : operation.inNamespace(namespace).withLabels(labels).list();
        return list.getItems();
    }

    /**
     * Lists the objects of a type the primary resource owns: those carrying its owner-uid label, which kjucon puts
     * on everything created with {@link #newMetadata}, in any namespace.
     *
     * @param type of the objects
     * @param <S> the type
     * @return the objects
     */
    public <S extends HasMetadata> List<S> owned(final Class<S> type) {
        final Map<String, String> selector = Map.of(Labels.ownerUid(labelDomain), primary.getMetadata().getUid());
        final boolean namespaced = Namespaced.class.isAssignableFrom(type);
        if (informers.isInformed(type, namespaced ? namespace() : null)) {
            return informers.byOwner(type, primary.getMetadata().getUid());
        }
        final MixedOperation<S, KubernetesResourceList<S>, Resource<S>> operation = client.resources(type);
        return namespaced
                ? operation.inAnyNamespace().withLabels(selector).list().getItems()
                : operation.withLabels(selector).list().getItems();
    }

    /**
     * Applies the desired state of an object with server-side apply under the controller's field manager. What
     * the former names of the controller applied is then handed over to it, so that what it no longer applies
     * goes away.
     *
     * @param desired object, as built for this apply - typically with {@link #newMetadata}
     * @param <S> the type
     * @return the object as the API server returned it
     */
    public <S extends HasMetadata> S apply(final S desired) {
        final S applied = ServerSideApply.apply(client, controllerName, desired);
        if (formerNames.isEmpty() || applied == null) {
            return applied;
        }
        return ServerSideApply.takeOverManagers(client, applied, formerNames, controllerName);
    }

    /**
     * Deletes an object.
     *
     * @param resource to delete
     * @return true if it was there
     */
    public boolean delete(final HasMetadata resource) {
        final List<StatusDetails> deleted = client.resource(resource).delete();
        return deleted != null && !deleted.isEmpty();
    }

    /**
     * Deletes an object.
     *
     * @param type of the object
     * @param namespace of the object, null if it is cluster-scoped
     * @param name of the object
     * @param <S> the type
     * @return true if it was there
     */
    public <S extends HasMetadata> boolean delete(final Class<S> type,
                                                  final String namespace,
                                                  final String name) {
        final List<StatusDetails> deleted = resources(type, namespace).withName(name).delete();
        return deleted != null && !deleted.isEmpty();
    }

    /**
     * Updates the status of the primary resource. The updater is first given a copy of the resource as this
     * context has it; if it says nothing changed, nothing more is done - no read, no write, and no event for
     * every watcher. Otherwise the resource is read fresh from the API server, handed to the updater again, and
     * its status - only the status - applied.
     *
     * @param updater changes the status of the resource given, and tells whether anything changed; it may be
     *                called twice
     * @return the resource after the update, or null if it is gone
     */
    public P patchStatus(final Predicate<P> updater) {
        if (!updater.test(client.getKubernetesSerialization().clone(primary))) {
            return primary;
        }
        final P latest = getLive(primaryType, namespace(), request.name());
        if (latest == null) {
            return null;
        }
        // the fresh read is newer than what the context had: keep it, so that what follows - the finalizer
        // patch of a cleanup, typically - starts from it
        primary = latest;
        if (!updater.test(latest)) {
            return latest;
        }
        primary = ServerSideApply.applyStatus(client, controllerName, latest);
        if (!formerNames.isEmpty()) {
            // the status a former name applied is the controller's now: a condition it no longer sets goes away
            primary = ServerSideApply.takeOverManagers(client, primary, formerNames, controllerName);
        }
        return primary;
    }

    /**
     * Metadata for an object the primary resource owns, in its namespace: the name, an owner reference with
     * {@code controller=true} - so that the object is garbage collected with the resource and its events wake
     * the controller - and the labels and annotations {@link #ownerLabels()} and {@link #ownerAnnotations()}
     * describe.
     *
     * @param name of the object
     * @return new metadata
     */
    public ObjectMeta newMetadata(final String name) {
        return newMetadata(namespace(), name);
    }

    /**
     * Metadata for an object the primary resource owns, in a given namespace. An owner reference is added only
     * where Kubernetes allows one: to an owner in the same namespace, or to a cluster-scoped owner. Elsewhere
     * the owner annotations alone tie the object to the resource, so it is not garbage collected with it - a
     * composition deletes it on cleanup.
     *
     * @param namespace of the object, null if it is cluster-scoped
     * @param name of the object
     * @return new metadata
     */
    public ObjectMeta newMetadata(final String namespace,
                                  final String name) {
        final ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(ownerLabels())
                .withAnnotations(ownerAnnotations())
                .build();
        final String ownerNamespace = namespace();
        if (ownerNamespace == null || ownerNamespace.equals(namespace)) {
            meta.setOwnerReferences(new ArrayList<>(List.of(ownerReference())));
        }
        return meta;
    }

    /**
     * @return a controller owner reference to the primary resource
     */
    public OwnerReference ownerReference() {
        final ObjectMeta meta = primary.getMetadata();
        return new OwnerReferenceBuilder()
                .withApiVersion(primary.getApiVersion())
                .withKind(primary.getKind())
                .withName(meta.getName())
                .withUid(Objects.requireNonNull(meta.getUid(), "uid"))
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    /**
     * The labels of what the primary resource owns: the owner-uid and controller labels kjucon finds its objects
     * by, managed-by (the controller, unless turned off) and part-of (the resource's name, when it fits a label
     * value).
     *
     * @return a new mutable map
     */
    public Map<String, String> ownerLabels() {
        final Map<String, String> labels = new HashMap<>();
        labels.put(Labels.controller(labelDomain), controllerName);
        if (managedByLabel) {
            labels.put(Labels.MANAGED_BY, controllerName);
        }
        final String name = primary.getMetadata().getName();
        if (Names.isLabelValue(name)) {
            labels.put(Labels.PART_OF, name);
        }
        labels.put(Labels.ownerUid(labelDomain), Objects.requireNonNull(primary.getMetadata().getUid(), "uid"));
        return labels;
    }

    /**
     * The annotations of what the primary resource owns: its kind and namespace/name, by which events of objects
     * an owner reference cannot point from are mapped back to it.
     *
     * @return a new mutable map
     */
    public Map<String, String> ownerAnnotations() {
        final Map<String, String> annotations = new HashMap<>();
        annotations.put(Labels.ownerKind(labelDomain), Labels.kindOf(primaryType));
        annotations.put(Labels.owner(labelDomain), request.key());
        return annotations;
    }

    private <S extends HasMetadata> NonNamespaceOperation<S, KubernetesResourceList<S>, Resource<S>> resources(
            final Class<S> type,
            final String namespace) {
        final MixedOperation<S, KubernetesResourceList<S>, Resource<S>> operation = client.resources(type);
        return namespace == null ? operation : operation.inNamespace(namespace);
    }

    static String namespaceFor(final Class<?> type,
                                       final String namespace) {
        return Namespaced.class.isAssignableFrom(type) ? namespace : null;
    }
}
