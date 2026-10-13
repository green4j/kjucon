/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The objects a primary resource is composed of, as a graph of dependencies: a component is applied once all it
 * depends on is ready, and deleted once all which depends on it is gone. A component may itself be a custom
 * resource composed by another controller - that is how hierarchies are built.
 *
 * @param <P> type of the primary resource
 */
public final class Composition<P extends HasMetadata> {
    private final List<Component<P, ?>> order;
    private final Map<Component<P, ?>, List<Component<P, ?>>> dependents;
    private final List<MetadataDecorator<P>> decorators;

    private Composition(final List<Component<P, ?>> order,
                        final Map<Component<P, ?>, List<Component<P, ?>>> dependents,
                        final List<MetadataDecorator<P>> decorators) {
        this.order = order;
        this.dependents = dependents;
        this.decorators = decorators;
    }

    /**
     * @param <P> type of the primary resource
     * @return a new builder
     */
    public static <P extends HasMetadata> Builder<P> builder() {
        return new Builder<>();
    }

    /**
     * @return the components, every one after all it depends on; among independent components the order they
     *         were added in
     */
    public List<Component<P, ?>> components() {
        return order;
    }

    /**
     * @param component of this composition
     * @return the components which depend on it
     */
    public List<Component<P, ?>> dependentsOf(final Component<P, ?> component) {
        return dependents.getOrDefault(component, List.of());
    }

    void decorate(final ReconcileContext<P> context,
                  final Component<P, ?> component,
                  final ObjectMeta metadata) {
        for (final MetadataDecorator<P> decorator : decorators) {
            decorator.decorate(context, component, metadata);
        }
    }

    /**
     * Builds a {@link Composition}.
     *
     * @param <P> type of the primary resource
     */
    public static final class Builder<P extends HasMetadata> {
        private final List<Component<P, ?>> components = new ArrayList<>();
        private final List<MetadataDecorator<P>> decorators = new ArrayList<>();

        private Builder() {
        }

        /**
         * @param component to add
         * @param <R> type of its object
         * @return the component, to make others depend on it
         */
        public <R extends HasMetadata> Component<P, R> add(final Component<P, R> component) {
            components.add(Objects.requireNonNull(component, "component"));
            return component;
        }

        /**
         * Adds a component - {@code Component.of} with the type of the primary resource inferred.
         *
         * @param name of the object, unique among the objects of its type in the composition
         * @param type of the object
         * @param factory of its desired state
         * @param <R> type of the object
         * @return the component, to configure it further and make others depend on it
         */
        public <R extends HasMetadata> Component<P, R> component(final String name,
                                                                 final Class<R> type,
                                                                 final ComponentFactory<P, R> factory) {
            return add(Component.of(name, type, factory));
        }

        /**
         * @param decorator of the metadata of every object
         * @return this
         */
        public Builder<P> decorate(final MetadataDecorator<P> decorator) {
            decorators.add(Objects.requireNonNull(decorator, "decorator"));
            return this;
        }

        /**
         * Copies labels of the primary resource to every object - those of the keys given which it has.
         *
         * @param keys of the labels
         * @return this
         */
        public Builder<P> propagateLabels(final String... keys) {
            return decorate((context, component, metadata) ->
                    Metadata.addLabels(metadata, pick(context.primary().getMetadata().getLabels(), keys)));
        }

        /**
         * Copies annotations of the primary resource to every object - those of the keys given which it has.
         *
         * @param keys of the annotations
         * @return this
         */
        public Builder<P> propagateAnnotations(final String... keys) {
            return decorate((context, component, metadata) ->
                    Metadata.addAnnotations(metadata, pick(context.primary().getMetadata().getAnnotations(), keys)));
        }

        /**
         * @return the composition
         * @throws IllegalArgumentException if two components name the same object, a component depends on one
         *                                  which was not added, or the dependencies form a cycle
         */
        public Composition<P> build() {
            final Set<Component<P, ?>> added = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<String> keys = new HashSet<>();
            for (final Component<P, ?> component : components) {
                if (!added.add(component)) {
                    throw new IllegalArgumentException(component + " is added twice");
                }
                if (!keys.add(component.identity())) {
                    throw new IllegalArgumentException("Two components name " + component);
                }
            }
            final Map<Component<P, ?>, List<Component<P, ?>>> dependents = new HashMap<>();
            for (final Component<P, ?> component : components) {
                for (final Component<P, ?> dependency : component.dependencies()) {
                    if (!added.contains(dependency)) {
                        throw new IllegalArgumentException(component + " depends on " + dependency
                                + ", which is not in the composition");
                    }
                    dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(component);
                }
            }
            return new Composition<>(sort(components), freeze(dependents), List.copyOf(decorators));
        }

        // Kahn's algorithm; among the components whose dependencies are done, the earliest added goes first.
        private static <P extends HasMetadata> List<Component<P, ?>> sort(final List<Component<P, ?>> components) {
            final List<Component<P, ?>> result = new ArrayList<>(components.size());
            final Set<Component<P, ?>> done = Collections.newSetFromMap(new IdentityHashMap<>());
            final List<Component<P, ?>> pending = new ArrayList<>(components);
            while (!pending.isEmpty()) {
                Component<P, ?> next = null;
                for (final Component<P, ?> candidate : pending) {
                    if (done.containsAll(candidate.dependencies())) {
                        next = candidate;
                        break;
                    }
                }
                if (next == null) {
                    throw new IllegalArgumentException("The dependencies form a cycle among " + pending);
                }
                pending.remove(next);
                done.add(next);
                result.add(next);
            }
            return List.copyOf(result);
        }

        private static <P extends HasMetadata> Map<Component<P, ?>, List<Component<P, ?>>> freeze(
                final Map<Component<P, ?>, List<Component<P, ?>>> map) {
            final Map<Component<P, ?>, List<Component<P, ?>>> result = new IdentityHashMap<>();
            for (final Map.Entry<Component<P, ?>, List<Component<P, ?>>> e : map.entrySet()) {
                result.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }

        private static Map<String, String> pick(final Map<String, String> source,
                                                final String[] keys) {
            final Map<String, String> result = new LinkedHashMap<>();
            if (source != null) {
                for (final String key : keys) {
                    final String value = source.get(key);
                    if (value != null) {
                        result.put(key, value);
                    }
                }
            }
            return result;
        }
    }
}
