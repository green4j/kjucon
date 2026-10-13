/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespaced;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One object a primary resource is composed of: its type and name, how its desired state is built, what it must
 * wait for, and how its readiness is judged.
 * <pre>{@code
 * Component<WebApp, ConfigMap> config = Component.of(name + "-config", ConfigMap.class, this::config);
 * Component<WebApp, Deployment> deployment = Component.of(name, Deployment.class, this::deployment)
 *         .dependsOn(config)
 *         .rolloutOn(config);
 * }</pre>
 *
 * @param <P> type of the primary resource
 * @param <R> type of the object
 */
public final class Component<P extends HasMetadata, R extends HasMetadata> {
    private final String name;
    private final Class<R> type;
    private final ComponentFactory<P, R> factory;
    private final List<Component<P, ?>> dependsOn = new ArrayList<>();
    private final List<Component<P, ?>> rolloutOn = new ArrayList<>();
    private ReadinessCheck<? super R> readiness;
    private DeletePolicy deletePolicy = DeletePolicy.DELETE;
    private ApplyErrorPolicy applyErrors;
    private String namespace;
    private boolean namespaceSet;

    private Component(final String name,
                      final Class<R> type,
                      final ComponentFactory<P, R> factory) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * @param name of the object, unique among the objects of its type in the composition
     * @param type of the object
     * @param factory of its desired state
     * @param <P> type of the primary resource
     * @param <R> type of the object
     * @return a new component
     */
    public static <P extends HasMetadata, R extends HasMetadata> Component<P, R> of(
            final String name,
            final Class<R> type,
            final ComponentFactory<P, R> factory) {
        return new Component<>(name, type, factory);
    }

    /**
     * @param components which must be ready before this one is applied; it is deleted before them
     * @return this
     */
    @SafeVarargs
    public final Component<P, R> dependsOn(final Component<P, ?>... components) {
        for (final Component<P, ?> component : components) {
            if (component == this) {
                throw new IllegalArgumentException(this + " cannot depend on itself");
            }
            if (!dependsOn.contains(component)) {
                dependsOn.add(Objects.requireNonNull(component, "component"));
            }
        }
        return this;
    }

    /**
     * Rolls the pods of this workload - a Deployment, StatefulSet or DaemonSet - whenever the data of the given
     * components changes, by stamping a hash of their desired state on the pod template. Implies
     * {@link #dependsOn}.
     *
     * @param components typically ConfigMaps and Secrets the pods read
     * @return this
     */
    @SafeVarargs
    public final Component<P, R> rolloutOn(final Component<P, ?>... components) {
        dependsOn(components);
        for (final Component<P, ?> component : components) {
            if (!rolloutOn.contains(component)) {
                rolloutOn.add(component);
            }
        }
        return this;
    }

    /**
     * @param check of the object's readiness, instead of the one of its type
     * @return this
     */
    public Component<P, R> readyWhen(final ReadinessCheck<? super R> check) {
        this.readiness = Objects.requireNonNull(check, "check");
        return this;
    }

    /**
     * @param policy on removal; {@link DeletePolicy#DELETE} by default
     * @return this
     */
    public Component<P, R> deletePolicy(final DeletePolicy policy) {
        this.deletePolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * @param policy when the API server refuses to apply the object; null, the default, for the one of the
     *               reconciler
     * @return this
     */
    public Component<P, R> applyErrors(final ApplyErrorPolicy policy) {
        this.applyErrors = policy;
        return this;
    }

    /**
     * Puts the object in another namespace than the primary resource's - or, with null, makes it cluster-scoped.
     * Such an object cannot have an owner reference: it is tied to the resource by the owner annotations and is
     * deleted by the composition's cleanup, not by the garbage collector.
     *
     * @param value the namespace
     * @return this
     */
    public Component<P, R> namespace(final String value) {
        this.namespace = value;
        this.namespaceSet = true;
        return this;
    }

    /**
     * @return the name of the object
     */
    public String name() {
        return name;
    }

    /**
     * @return the type of the object
     */
    public Class<R> type() {
        return type;
    }

    /**
     * @return the components this one waits for
     */
    public List<Component<P, ?>> dependencies() {
        return Collections.unmodifiableList(dependsOn);
    }

    /**
     * @return the delete policy
     */
    public DeletePolicy deletePolicy() {
        return deletePolicy;
    }

    /**
     * @return the policy when the API server refuses to apply the object, null for the one of the reconciler
     */
    public ApplyErrorPolicy applyErrors() {
        return applyErrors;
    }

    ComponentFactory<P, R> factory() {
        return factory;
    }

    List<Component<P, ?>> rolloutSources() {
        return Collections.unmodifiableList(rolloutOn);
    }

    ReadinessCheck<? super R> readiness() {
        return readiness;
    }

    /**
     * @param primaryNamespace of the primary resource
     * @return the namespace of the object, null if it is cluster-scoped
     */
    String namespaceFor(final String primaryNamespace) {
        if (!Namespaced.class.isAssignableFrom(type)) {
            return null;
        }
        return namespaceSet ? namespace : primaryNamespace;
    }

    /**
     * @param primaryNamespace of the primary resource
     * @return Kind.group/namespace/name - what identifies the object in the composition
     */
    String key(final String primaryNamespace) {
        return key(kind(HasMetadata.getKind(type), HasMetadata.getGroup(type)), namespaceFor(primaryNamespace), name);
    }

    /**
     * @return what identifies the object before the namespace of the primary resource is known
     */
    String identity() {
        return kind(HasMetadata.getKind(type), HasMetadata.getGroup(type)) + '/'
                + (namespaceSet ? String.valueOf(namespace) : "~") + '/' + name;
    }

    /**
     * @param object a composition made
     * @return what identifies it, as {@link #key(String)} does for a component
     */
    static String keyOf(final HasMetadata object) {
        final String apiVersion = object.getApiVersion();
        final int slash = apiVersion == null ? -1 : apiVersion.indexOf('/');
        final String group = slash < 0 ? "" : apiVersion.substring(0, slash);
        return key(kind(object.getKind(), group), object.getMetadata().getNamespace(), object.getMetadata().getName());
    }

    private static String kind(final String kind,
                               final String group) {
        return group == null || group.isEmpty() ? kind : kind + '.' + group;
    }

    private static String key(final String kind,
                              final String namespace,
                              final String name) {
        return kind + '/' + (namespace == null ? "" : namespace) + '/' + name;
    }

    @Override
    public String toString() {
        return HasMetadata.getKind(type) + '/' + name;
    }
}
