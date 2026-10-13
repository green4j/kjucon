/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ExceptionHandler;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The informers of an operator: one per type and namespace, shared by every controller which reads or watches
 * the type - a resource which is the primary of one controller and a secondary of another is listed and watched
 * once. Every informer indexes its objects by the owner-uid label kjucon puts on what a primary resource owns -
 * in its namespace or any other, or cluster-scoped - which is how {@link ReconcileContext#owned} finds them
 * without a pass over the whole cache or a call to the API server.
 * <p>
 * All the types are registered before {@link #start()}; reads of a type or namespace which is not informed return
 * nothing, and the callers fall back to the API server.
 */
final class InformerRegistry implements AutoCloseable {
    static final String OWNER_UID_INDEX = "kjucon-owner-uid";

    private final KubernetesClient client;
    private final Set<String> namespaces; // null: all
    private final String ownerUidLabel;
    private final ControllerObserver observer;
    private final Observed observed;
    private final Map<Class<?>, TypeInformers<?>> types = new LinkedHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    private volatile boolean started;

    InformerRegistry(final KubernetesClient client,
                     final Set<String> namespaces,
                     final String labelDomain,
                     final ControllerObserver observer,
                     final Observed observed) {
        this.client = client;
        this.namespaces = namespaces;
        this.ownerUidLabel = Labels.ownerUid(labelDomain);
        this.observer = observer;
        this.observed = observed;
    }

    /**
     * Registers a type to inform, with an optional handler of its events.
     *
     * @param type to inform
     * @param resyncMillis how often to resync, 0 for never; the shortest of all the registrations wins
     * @param handler of the events, may be null
     * @param <T> the type
     */
    synchronized <T extends HasMetadata> void register(final Class<T> type,
                                                      final long resyncMillis,
                                                      final ResourceEventHandler<T> handler) {
        if (started) {
            throw new IllegalStateException("Informers are already started");
        }
        @SuppressWarnings("unchecked")
        final TypeInformers<T> informers = (TypeInformers<T>) types.computeIfAbsent(type, TypeInformers::new);
        if (resyncMillis > 0 && (informers.resyncMillis == 0 || resyncMillis < informers.resyncMillis)) {
            informers.resyncMillis = resyncMillis;
        }
        if (handler != null) {
            informers.handlers.add(handler);
        }
    }

    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        for (final TypeInformers<?> informers : types.values()) {
            informers.start();
        }
    }

    /**
     * @param type to check
     * @param namespace to check, null for a cluster-scoped type
     * @return true if objects of the type in the namespace are in the cache
     */
    boolean isInformed(final Class<?> type,
                       final String namespace) {
        final TypeInformers<?> informers = types.get(type);
        return informers != null && informers.informerFor(namespace) != null;
    }

    /**
     * @param type of the object
     * @param namespace of the object, null for a cluster-scoped one
     * @param name of the object
     * @param <T> the type
     * @return the cached object, or null - also when the type or namespace is not informed
     */
    <T extends HasMetadata> T get(final Class<T> type,
                                  final String namespace,
                                  final String name) {
        final SharedIndexInformer<T> informer = informerFor(type, namespace);
        return informer == null ? null : informer.getIndexer().getByKey(ReconcileRequest.key(namespace, name));
    }

    /**
     * @param type of the objects
     * @param namespace of the objects, null for all the informed namespaces
     * @param <T> the type
     * @return the cached objects
     */
    <T extends HasMetadata> List<T> list(final Class<T> type,
                                         final String namespace) {
        @SuppressWarnings("unchecked")
        final TypeInformers<T> informers = (TypeInformers<T>) types.get(type);
        if (informers == null) {
            return List.of();
        }
        if (namespace == null || informers.clusterScoped) {
            final List<T> result = new ArrayList<>();
            for (final SharedIndexInformer<T> informer : informers.informers) {
                result.addAll(informer.getIndexer().list());
            }
            return result;
        }
        final SharedIndexInformer<T> informer = informers.informerFor(namespace);
        if (informer == null) {
            return List.of();
        }
        return informer.getIndexer().byIndex("namespace", namespace);
    }

    /**
     * @param type of the objects
     * @param ownerUid uid of the owner
     * @param <T> the type
     * @return the cached objects carrying the owner-uid label of the uid, in every informed namespace
     */
    <T extends HasMetadata> List<T> byOwner(final Class<T> type,
                                            final String ownerUid) {
        @SuppressWarnings("unchecked")
        final TypeInformers<T> informers = (TypeInformers<T>) types.get(type);
        if (informers == null) {
            return List.of();
        }
        final List<T> result = new ArrayList<>();
        for (final SharedIndexInformer<T> informer : informers.informers) {
            result.addAll(informer.getIndexer().byIndex(OWNER_UID_INDEX, ownerUid));
        }
        return result;
    }

    boolean hasSynced() {
        if (!started) {
            return false;
        }
        for (final TypeInformers<?> informers : types.values()) {
            if (!informers.hasSynced()) {
                return false;
            }
        }
        return true;
    }

    List<String> failed() {
        final List<String> result = new ArrayList<>(failed);
        Collections.sort(result);
        return result;
    }

    @Override
    public synchronized void close() {
        for (final TypeInformers<?> informers : types.values()) {
            informers.stop();
        }
    }

    private <T extends HasMetadata> SharedIndexInformer<T> informerFor(final Class<T> type,
                                                                       final String namespace) {
        @SuppressWarnings("unchecked")
        final TypeInformers<T> informers = (TypeInformers<T>) types.get(type);
        return informers == null ? null : informers.informerFor(namespace);
    }

    private List<String> ownerUids(final HasMetadata resource) {
        final Map<String, String> labels = resource.getMetadata().getLabels();
        final String uid = labels == null ? null : labels.get(ownerUidLabel);
        return uid == null ? List.of() : List.of(uid);
    }

    private final class TypeInformers<T extends HasMetadata> {
        private final Class<T> type;
        private final boolean clusterScoped;
        private final List<ResourceEventHandler<T>> handlers = new ArrayList<>();
        private final List<SharedIndexInformer<T>> informers = new ArrayList<>();
        private final Map<String, SharedIndexInformer<T>> byNamespace = new LinkedHashMap<>();
        private long resyncMillis;
        private SharedIndexInformer<T> all;
        private volatile boolean reportedSynced;

        private TypeInformers(final Class<?> type) {
            @SuppressWarnings("unchecked")
            final Class<T> typed = (Class<T>) type;
            this.type = typed;
            this.clusterScoped = !Namespaced.class.isAssignableFrom(type);
        }

        private void start() {
            if (clusterScoped) {
                all = create(client.resources(type).runnableInformer(resyncMillis));
            } else if (namespaces == null) {
                all = create(client.resources(type).inAnyNamespace().runnableInformer(resyncMillis));
            } else {
                for (final String namespace : namespaces) {
                    byNamespace.put(namespace,
                            create(client.resources(type).inNamespace(namespace).runnableInformer(resyncMillis)));
                }
            }
            for (final SharedIndexInformer<T> informer : informers) {
                informer.start().whenComplete((ignored, error) -> {
                    if (error == null && hasSynced() && !reportedSynced) {
                        reportedSynced = true;
                        observed.by(observer, o -> o.onInformerSynced(type));
                    }
                });
            }
        }

        private SharedIndexInformer<T> create(final SharedIndexInformer<T> informer) {
            final Map<String, Function<T, List<String>>> indexers = new LinkedHashMap<>();
            indexers.put(OWNER_UID_INDEX, InformerRegistry.this::ownerUids);
            informer.addIndexers(indexers);
            // Keep trying whatever the failure - a CRD installed after the operator started, an API server
            // restarting - except a resource which does not fit its class: retrying cannot fix that.
            informer.exceptionHandler((isStarted, t) -> {
                final boolean retry = !ExceptionHandler.isDeserializationException(t);
                observed.error(type.getSimpleName(),
                        retry ? "The informer failed, retrying" : "The informer failed for good", t);
                observed.by(observer, o -> o.onInformerFailed(type, retry));
                return retry;
            });
            for (final ResourceEventHandler<T> handler : handlers) {
                informer.addEventHandler(handler);
            }
            informer.stopped().whenComplete((ignored, error) -> {
                if (error != null) {
                    observed.error(type.getSimpleName(), "The informer stopped", error);
                    failed.add(type.getName());
                }
            });
            informers.add(informer);
            return informer;
        }

        private SharedIndexInformer<T> informerFor(final String namespace) {
            if (all != null) {
                return all;
            }
            return namespace == null ? null : byNamespace.get(namespace);
        }

        private boolean hasSynced() {
            for (final SharedIndexInformer<T> informer : informers) {
                if (!informer.hasSynced()) {
                    return false;
                }
            }
            return true;
        }

        private void stop() {
            for (final SharedIndexInformer<T> informer : informers) {
                informer.stop();
            }
        }
    }
}
