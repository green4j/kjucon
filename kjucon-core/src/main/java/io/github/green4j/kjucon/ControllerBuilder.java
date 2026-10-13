/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.util.Names;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configures one controller of an {@link Operator}; obtained from {@link Operator.Builder#controller}.
 *
 * @param <P> type of the primary resource
 */
public final class ControllerBuilder<P extends HasMetadata> {
    private final Class<P> type;
    private final Reconciler<P> reconciler;
    private final List<WatchSpec<?>> watches = new ArrayList<>();
    private final List<EventSource> eventSources = new ArrayList<>();

    private String name;
    private int workers = 1;
    private boolean finalizerEnabled = true;
    private String finalizer;
    private final Set<String> formerFinalizers = new LinkedHashSet<>();
    private boolean formerDefaultFinalizer;
    private final Set<String> formerNames = new LinkedHashSet<>();
    private boolean managedByLabel = true;
    private EventFilter<P> filter = EventFilter.defaults();
    private Map<String, String> selector = Map.of();
    private RateLimiter retry;
    private Duration resyncPeriod = Duration.ZERO;
    private ControllerObserver observer;

    ControllerBuilder(final Class<P> type,
                      final Reconciler<P> reconciler) {
        this.type = Objects.requireNonNull(type, "type");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.name = HasMetadata.getKind(type).toLowerCase(Locale.ROOT);
    }

    /**
     * @param controllerName unique within the operator, and a valid label value (at most 63 characters of
     *                       letters, digits, '-', '_' and '.', beginning and ending with a letter or digit): it
     *                       is also the field manager of everything the controller applies, the value of the
     *                       controller label on it, and the default finalizer. Defaults to the lowercase kind of
     *                       the primary resource
     * @return this
     */
    public ControllerBuilder<P> name(final String controllerName) {
        this.name = Objects.requireNonNull(controllerName, "name");
        return this;
    }

    /**
     * @param count of the threads reconciling different resources in parallel; 1 by default
     * @return this
     */
    public ControllerBuilder<P> workers(final int count) {
        if (count < 1) {
            throw new IllegalArgumentException("workers must be positive: " + count);
        }
        this.workers = count;
        return this;
    }

    /**
     * @param enabled whether the controller holds its resources with a finalizer until
     *                {@link Reconciler#cleanup} succeeds; enabled by default, with the name
     *                {@code <labelDomain>/<controller name>}
     * @return this
     */
    public ControllerBuilder<P> finalizer(final boolean enabled) {
        this.finalizerEnabled = enabled;
        return this;
    }

    /**
     * @param finalizerName to use instead of the default; must be domain-qualified
     * @return this
     */
    public ControllerBuilder<P> finalizer(final String finalizerName) {
        if (!HasMetadata.validateFinalizer(finalizerName)) {
            throw new IllegalArgumentException("Invalid finalizer name: " + finalizerName);
        }
        this.finalizerEnabled = true;
        this.finalizer = finalizerName;
        return this;
    }

    /**
     * Finalizers the controller held its resources with before: after the controller or its finalizer was
     * renamed, or its finalizer disabled. A live resource holding one gets the current finalizer instead; a
     * resource being deleted holding one is cleaned up, and then let go. Without this, a resource keeping a
     * finalizer nobody removes any more stays in Terminating for good.
     *
     * @param finalizerNames which the controller used before
     * @return this
     */
    public ControllerBuilder<P> formerFinalizers(final String... finalizerNames) {
        for (final String finalizerName : finalizerNames) {
            if (!HasMetadata.validateFinalizer(finalizerName)) {
                throw new IllegalArgumentException("Invalid finalizer name: " + finalizerName);
            }
            formerFinalizers.add(finalizerName);
        }
        return this;
    }

    /**
     * Names the controller had before. What it applied under one of them is its own: it is pruned, deleted and
     * released like what it applies now, the fields the former name owns are handed over to the controller's
     * field manager on the next apply - so that what is no longer applied goes away - and the default finalizer of
     * the former name, {@code <labelDomain>/<former name>}, counts among the former finalizers. A former name must not be the name of another controller of the operator.
     *
     * @param controllerNames which the controller had before
     * @return this
     */
    public ControllerBuilder<P> formerNames(final String... controllerNames) {
        for (final String controllerName : controllerNames) {
            if (controllerName == null || controllerName.isEmpty() || !Names.isLabelValue(controllerName)) {
                throw new IllegalArgumentException("Invalid controller name: '" + controllerName + "'");
            }
            formerNames.add(controllerName);
        }
        return this;
    }

    /**
     * @param enabled whether what the controller applies carries {@code app.kubernetes.io/managed-by} with the
     *                controller's name; enabled by default. Turn it off where another tool sets that label, a
     *                Helm chart or Argo CD: kjucon finds its objects by labels of its own domain, and the
     *                recommended label is only for people and tools
     * @return this
     */
    public ControllerBuilder<P> managedByLabel(final boolean enabled) {
        this.managedByLabel = enabled;
        return this;
    }

    /**
     * Counts the default finalizer, {@code <labelDomain>/<controller name>}, among the former ones - for a
     * controller which held its resources with it before, and holds them with none or another one now.
     *
     * @return this
     */
    public ControllerBuilder<P> formerDefaultFinalizer() {
        this.formerDefaultFinalizer = true;
        return this;
    }

    /**
     * @param eventFilter deciding which updates of a primary resource are reconciled; see
     *                    {@link EventFilter#defaults()}
     * @return this
     */
    public ControllerBuilder<P> filter(final EventFilter<P> eventFilter) {
        this.filter = Objects.requireNonNull(eventFilter, "filter");
        return this;
    }

    /**
     * @param labels a primary resource must carry to be reconciled by this controller; the others are ignored.
     *               A resource which carries the controller's finalizer is still cleaned up when it is deleted,
     *               even if it lost the labels meanwhile - otherwise it could never go
     * @return this
     */
    public ControllerBuilder<P> selector(final Map<String, String> labels) {
        this.selector = Map.copyOf(labels);
        return this;
    }

    /**
     * @param rateLimiter deciding when a failed reconcile is retried; {@link RateLimiter#defaults()} by default
     * @return this
     */
    public ControllerBuilder<P> retry(final RateLimiter rateLimiter) {
        this.retry = Objects.requireNonNull(rateLimiter, "retry");
        return this;
    }

    /**
     * @param period after which every primary resource is reconciled again, changed or not; none by default
     * @return this
     */
    public ControllerBuilder<P> resyncPeriod(final Duration period) {
        this.resyncPeriod = Objects.requireNonNull(period, "period");
        return this;
    }

    /**
     * Reconciles the primary resources a mapper names whenever a secondary resource changes.
     *
     * @param secondaryType to watch
     * @param mapper from a secondary resource to the primary ones
     * @param <S> type of the secondary resource
     * @return this
     */
    public <S extends HasMetadata> ControllerBuilder<P> watches(final Class<S> secondaryType,
                                                                final SecondaryMapper<S> mapper) {
        watches.add(new WatchSpec<>(secondaryType, Objects.requireNonNull(mapper, "mapper")));
        return this;
    }

    /**
     * Reconciles the owner whenever a resource it owns changes - found by owner reference, or by the owner
     * annotations kjucon puts on what a context's {@link ReconcileContext#newMetadata} describes.
     *
     * @param secondaryType owned
     * @param <S> type of the secondary resource
     * @return this
     */
    public <S extends HasMetadata> ControllerBuilder<P> owns(final Class<S> secondaryType) {
        watches.add(new WatchSpec<>(secondaryType, null));
        return this;
    }

    /**
     * @param source of events Kubernetes does not know about
     * @return this
     */
    public ControllerBuilder<P> eventSource(final EventSource source) {
        eventSources.add(Objects.requireNonNull(source, "source"));
        return this;
    }

    /**
     * @param controllerObserver of this controller, instead of the operator's
     * @return this
     */
    public ControllerBuilder<P> observer(final ControllerObserver controllerObserver) {
        this.observer = controllerObserver;
        return this;
    }

    String name() {
        return name;
    }

    Controller<P> build(final KubernetesClient client,
                        final InformerRegistry informers,
                        final String labelDomain,
                        final ControllerObserver operatorObserver,
                        final Observed observed) {
        if (name.isEmpty() || !Names.isLabelValue(name)) {
            throw new IllegalArgumentException("Invalid controller name: '" + name
                    + "'; it must be a label value, being the field manager, a label and the finalizer");
        }
        final String finalizerName;
        if (!finalizerEnabled) {
            finalizerName = null;
        } else if (finalizer != null) {
            finalizerName = finalizer;
        } else {
            finalizerName = labelDomain + '/' + name;
            if (!HasMetadata.validateFinalizer(finalizerName)) {
                throw new IllegalArgumentException("Invalid default finalizer name: " + finalizerName
                        + "; give the controller a valid name, or a finalizer name");
            }
        }
        final Set<String> formerNamesResolved = new LinkedHashSet<>(formerNames);
        formerNamesResolved.remove(name);
        final Set<String> former = new LinkedHashSet<>(formerFinalizers);
        if (formerDefaultFinalizer) {
            former.add(labelDomain + '/' + name);
        }
        for (final String formerName : formerNamesResolved) {
            final String formerFinalizer = labelDomain + '/' + formerName;
            if (HasMetadata.validateFinalizer(formerFinalizer)) {
                former.add(formerFinalizer);
            }
        }
        if (finalizerName != null) {
            former.remove(finalizerName);
        }
        final List<Controller.Watch<?>> resolved = new ArrayList<>(watches.size());
        for (final WatchSpec<?> watch : watches) {
            resolved.add(watch.resolve(labelDomain, type));
        }
        return new Controller<>(
                name,
                type,
                reconciler,
                workers,
                finalizerName,
                List.copyOf(former),
                List.copyOf(formerNamesResolved),
                managedByLabel,
                filter,
                selector,
                retry != null ? retry : RateLimiter.defaults(),
                resyncPeriod.toMillis(),
                resolved,
                List.copyOf(eventSources),
                observer != null ? observer : operatorObserver,
                observed,
                client,
                informers,
                labelDomain);
    }

    private static final class WatchSpec<S extends HasMetadata> {
        private final Class<S> type;
        private final SecondaryMapper<S> mapper;

        private WatchSpec(final Class<S> type,
                          final SecondaryMapper<S> mapper) {
            this.type = Objects.requireNonNull(type, "type");
            this.mapper = mapper;
        }

        private Controller.Watch<S> resolve(final String labelDomain,
                                            final Class<? extends HasMetadata> ownerType) {
            return new Controller.Watch<>(type,
                    mapper != null ? mapper : SecondaryMapper.byOwner(labelDomain, ownerType));
        }
    }
}
