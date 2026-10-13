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
 * What a controller is asked to reconcile: the namespace and the name of a primary resource, nothing more. The
 * resource itself is read from the cache when the request is processed, so a request queued long ago still sees
 * the latest state, and any number of events about one resource collapse into one request.
 */
public final class ReconcileRequest {
    private final String namespace;
    private final String name;
    private final int hash;

    /**
     * @param namespace of the resource, null if it is cluster-scoped
     * @param name of the resource
     */
    public ReconcileRequest(final String namespace,
                            final String name) {
        this.namespace = namespace;
        this.name = Objects.requireNonNull(name, "name");
        this.hash = Objects.hash(namespace, name);
    }

    /**
     * @param resource to reconcile
     * @return a request for the resource
     */
    public static ReconcileRequest of(final HasMetadata resource) {
        return new ReconcileRequest(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
    }

    /**
     * @return the namespace, null if the resource is cluster-scoped
     */
    public String namespace() {
        return namespace;
    }

    /**
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * @return namespace/name, the key informers index the resource by
     */
    public String key() {
        return key(namespace, name);
    }

    // the key informers index an object by: namespace/name, or the name alone if it is cluster-scoped
    static String key(final String namespace,
                      final String name) {
        Objects.requireNonNull(name, "name");
        return namespace != null ? namespace + '/' + name : name;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReconcileRequest)) {
            return false;
        }
        final ReconcileRequest that = (ReconcileRequest) o;
        return name.equals(that.name) && Objects.equals(namespace, that.namespace);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return key();
    }
}
