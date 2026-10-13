/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.github.green4j.kjucon.util.Names;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * A set of controllers sharing one client, one set of informers and one leader election.
 * <pre>{@code
 * Operator.Builder builder = Operator.builder()
 *         .client(client)
 *         .watchNamespace("apps");
 * builder.controller(WebApp.class, new WebAppReconciler())
 *         .owns(Deployment.class)
 *         .workers(2);
 * try (Operator operator = builder.build()) {
 *     operator.start();
 *     ...
 * }
 * }</pre>
 * {@link #start()} returns at once: informers list and watch in the background, and the controllers begin to
 * reconcile once every informer has synced - and, with leader election, once this instance leads.
 */
public final class Operator implements AutoCloseable {
    // the sources of the errors which are no controller's
    private static final String LEADER_ELECTION = "leader-election";
    private static final String OPERATOR = "operator";
    private static final String CREATE = "create";
    private static final List<String> LEASE_VERBS = List.of("get", CREATE, "update");

    private final KubernetesClient client;
    private final Set<String> namespaces;
    private final String labelDomain;
    private final InformerRegistry informers;
    private final Map<String, Controller<?>> controllers;
    private final LeaderElection leaderElection;
    private final ApiServerProbe apiServerProbe;
    private final ControllerObserver observer;
    private final Observed observed;
    private final IntConsumer exit;

    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean leader;
    private volatile boolean leadershipLost;
    private CompletableFuture<?> election;

    private Operator(final Builder builder) {
        this.client = Objects.requireNonNull(builder.client, "client");
        this.labelDomain = builder.labelDomain;
        this.leaderElection = builder.leaderElection;
        if (builder.allNamespaces) {
            this.namespaces = null;
        } else if (!builder.namespaces.isEmpty()) {
            this.namespaces = Set.copyOf(builder.namespaces);
        } else {
            final String namespace = client.getNamespace();
            this.namespaces = namespace == null ? null : Set.of(namespace);
        }
        this.observer = builder.observer;
        this.observed = new Observed(builder.errorHandler);
        this.exit = builder.exit;
        this.informers = new InformerRegistry(client, namespaces, labelDomain, observer, observed);
        this.controllers = new LinkedHashMap<>();
        for (final ControllerBuilder<?> controllerBuilder : builder.controllers) {
            final Controller<?> controller = controllerBuilder.build(client, informers, labelDomain, observer,
                    observed);
            if (controllers.putIfAbsent(controller.name(), controller) != null) {
                throw new IllegalArgumentException("Two controllers named " + controller.name());
            }
            controller.register();
        }
        for (final Controller<?> controller : controllers.values()) {
            for (final String former : controller.formerNames()) {
                if (controllers.containsKey(former)) {
                    throw new IllegalArgumentException("Controller " + controller.name() + " has a former name "
                            + former + " which another controller has now");
                }
            }
            final Set<String> siblings = new HashSet<>(controllers.keySet());
            siblings.remove(controller.name());
            controller.siblingNames(siblings);
        }
        this.apiServerProbe = builder.apiServerProbeInterval != null
                ? new ApiServerProbe(client, builder.apiServerProbeInterval) : null;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the informers, the API server probe and - at once, or once this instance leads - the controllers.
     */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        informers.start();
        if (apiServerProbe != null) {
            apiServerProbe.start();
        }
        final List<String> names = List.copyOf(controllers.keySet());
        observed.by(observer, o -> o.onOperatorStarted(namespaces, names));
        if (leaderElection == null) {
            startControllers();
            return;
        }
        checkLeaseAccess();
        election = client.leaderElector()
                .withConfig(new LeaderElectionConfigBuilder()
                        .withName(leaderElection.name())
                        .withLock(new LeaseLock(leaderElection.namespace(), leaderElection.name(),
                                leaderElection.identity()))
                        .withLeaseDuration(leaderElection.leaseDuration())
                        .withRenewDeadline(leaderElection.renewDeadline())
                        .withRetryPeriod(leaderElection.retryPeriod())
                        .withReleaseOnCancel()
                        .withLeaderCallbacks(new LeaderCallbacks(this::onStartLeading, this::onStopLeading,
                                id -> observed.by(observer, o -> o.onLeaderChanged(id))))
                        .build())
                .build()
                .start();
        // fabric8 retries a failure to take or renew the lease itself, logging it with its own logger; the election
        // ends with an error only on something else
        election.whenComplete((ignored, error) -> {
            if (error != null && !(error instanceof CancellationException)) {
                observed.error(LEADER_ELECTION, "The leader election stopped", error);
            }
        });
    }

    /**
     * Tells up front what fabric8 would only log on every attempt: that the operator may not take the lease, so it
     * will never lead. Best effort: when the access cannot be reviewed, nothing is told.
     */
    private void checkLeaseAccess() {
        final List<String> denied = new ArrayList<>();
        for (final String verb : LEASE_VERBS) {
            final SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
                    .withNewSpec()
                    .withNewResourceAttributes()
                    .withGroup("coordination.k8s.io")
                    .withResource("leases")
                    .withNamespace(leaderElection.namespace())
                    .withName(CREATE.equals(verb) ? null : leaderElection.name()) // creating, it has no name yet
                    .withVerb(verb)
                    .endResourceAttributes()
                    .endSpec()
                    .build();
            final SelfSubjectAccessReview reviewed;
            try {
                reviewed = client.authorization().v1().selfSubjectAccessReview().create(review);
            } catch (final RuntimeException e) {
                return;
            }
            if (reviewed == null || reviewed.getStatus() == null) {
                return;
            }
            if (!Boolean.TRUE.equals(reviewed.getStatus().getAllowed())) {
                denied.add(verb);
            }
        }
        if (!denied.isEmpty()) {
            observed.error(LEADER_ELECTION, "No access to the lease " + leaderElection.namespace() + '/'
                    + leaderElection.name() + " to " + String.join(", ", denied)
                    + "; this instance will never lead", null);
        }
    }

    /**
     * @param name of a controller
     * @return the controller, or null if there is none of the name
     */
    public Controller<?> controller(final String name) {
        return controllers.get(name);
    }

    /**
     * @return the controllers, in the order they were configured
     */
    public Collection<Controller<?>> controllers() {
        return List.copyOf(controllers.values());
    }

    /**
     * @return the client
     */
    public KubernetesClient client() {
        return client;
    }

    /**
     * @return the domain of the operator's labels, annotations and finalizers
     */
    public String labelDomain() {
        return labelDomain;
    }

    /**
     * @return a snapshot of the state of the operator
     */
    public OperatorHealth health() {
        final List<ControllerHealth> controllerHealths = new ArrayList<>(controllers.size());
        for (final Controller<?> controller : controllers.values()) {
            controllerHealths.add(controller.health());
        }
        return new OperatorHealth(
                started && !closed,
                leaderElection != null,
                leader,
                leadershipLost,
                informers.hasSynced(),
                informers.failed(),
                apiServerProbe != null ? apiServerProbe.reachable() : null,
                apiServerProbe != null ? apiServerProbe.message() : null,
                controllerHealths);
    }

    /**
     * Stops the controllers, the leader election - releasing the lease if this instance holds it - and the
     * informers, and closes the reconcilers. The client is not closed: it was given, not created.
     */
    @Override
    public void close() {
        final CompletableFuture<?> runningElection;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            runningElection = election;
        }
        if (runningElection != null) {
            runningElection.cancel(true);
        }
        stopControllers();
        for (final Controller<?> controller : controllers.values()) {
            controller.closeReconciler();
        }
        if (apiServerProbe != null) {
            apiServerProbe.close();
        }
        informers.close();
        observed.by(observer, ControllerObserver::onOperatorStopped);
    }

    private void startControllers() {
        for (final Controller<?> controller : controllers.values()) {
            controller.start(informers::hasSynced);
        }
    }

    private void stopControllers() {
        for (final Controller<?> controller : controllers.values()) {
            controller.close();
        }
    }

    private void onStartLeading() {
        if (closed) {
            return;
        }
        leader = true;
        observed.by(observer, o -> o.onLeadershipAcquired(leaderElection.identity()));
        startControllers();
    }

    private void onStopLeading() {
        final boolean wasLeader = leader;
        leader = false;
        if (closed || !wasLeader) {
            return; // released on close, or never led
        }
        leadershipLost = true;
        observed.by(observer, o -> o.onLeadershipLost(leaderElection.identity()));
        // Another instance may lead already: stop at once, but not on the election's thread, and exit even if a
        // reconcile does not stop in time.
        final Thread stopping = new Thread(this::stopAfterLoss, "kjucon-leadership-lost");
        stopping.setDaemon(true);
        stopping.start();
    }

    private void stopAfterLoss() {
        try {
            stopControllers();
            final Runnable onLost = leaderElection.onLost();
            if (onLost != null) {
                onLost.run();
            }
        } catch (final RuntimeException e) {
            observed.error(OPERATOR, "Stopping after the loss of the leadership failed", e);
        } finally {
            if (leaderElection.exitOnLost()) {
                exit.accept(1);
            }
        }
    }

    /**
     * Configures an {@link Operator}.
     */
    public static final class Builder {
        private final List<ControllerBuilder<?>> controllers = new ArrayList<>();
        private final Set<String> namespaces = new LinkedHashSet<>();
        private KubernetesClient client;
        private boolean allNamespaces;
        private String labelDomain = Labels.DEFAULT_DOMAIN;
        private ControllerObserver observer;
        private ErrorHandler errorHandler = new StdErrErrorHandler();
        private IntConsumer exit = System::exit;
        private LeaderElection leaderElection;
        private Duration apiServerProbeInterval;

        private Builder() {
        }

        /**
         * @param kubernetesClient to use; it stays the caller's to close
         * @return this
         */
        public Builder client(final KubernetesClient kubernetesClient) {
            this.client = Objects.requireNonNull(kubernetesClient, "client");
            return this;
        }

        /**
         * Watches one namespace. Without any namespace configured, the namespace of the client is watched, or
         * all of them if the client has none.
         *
         * @param namespace to watch
         * @return this
         */
        public Builder watchNamespace(final String namespace) {
            namespaces.add(Objects.requireNonNull(namespace, "namespace"));
            return this;
        }

        /**
         * @param namespaceNames to watch
         * @return this
         */
        public Builder watchNamespaces(final String... namespaceNames) {
            namespaces.addAll(Arrays.asList(namespaceNames));
            return this;
        }

        /**
         * Watches every namespace - the operator needs to list and watch its types cluster-wide.
         *
         * @return this
         */
        public Builder watchAllNamespaces() {
            this.allNamespaces = true;
            return this;
        }

        /**
         * @param domain of the labels, annotations and finalizers, a DNS-1123 subdomain;
         *               {@link Labels#DEFAULT_DOMAIN} by default
         * @return this
         */
        public Builder labelDomain(final String domain) {
            Objects.requireNonNull(domain, "labelDomain");
            if (!Names.isDns1123Subdomain(domain)) {
                throw new IllegalArgumentException("Invalid label domain: '" + domain
                        + "'; it must be a DNS-1123 subdomain, being the prefix of labels, annotations and finalizers");
            }
            this.labelDomain = domain;
            return this;
        }

        /**
         * @param controllerObserver of every controller which is not given its own
         * @return this
         */
        public Builder observer(final ControllerObserver controllerObserver) {
            this.observer = controllerObserver;
            return this;
        }

        /**
         * @param handler of every failure; {@link StdErrErrorHandler} by default
         * @return this
         */
        public Builder errorHandler(final ErrorHandler handler) {
            this.errorHandler = Objects.requireNonNull(handler, "errorHandler");
            return this;
        }

        // for the tests: what exits the process
        Builder exit(final IntConsumer action) {
            this.exit = Objects.requireNonNull(action, "exit");
            return this;
        }

        /**
         * @param election to take part in; none by default
         * @return this
         */
        public Builder leaderElection(final LeaderElection election) {
            this.leaderElection = election;
            return this;
        }

        /**
         * @param interval to ask the API server for its version at, so that {@link OperatorHealth} tells whether
         *                 it is reachable; not probed by default
         * @return this
         */
        public Builder apiServerProbe(final Duration interval) {
            this.apiServerProbeInterval = interval;
            return this;
        }

        /**
         * Adds a controller. The reconciler configures it first ({@link Reconciler#configure}); the operator closes
         * the reconciler when it is closed itself.
         *
         * @param type of the primary resource
         * @param reconciler of it
         * @param <P> the type
         * @return the builder of the controller, to configure it further
         */
        public <P extends HasMetadata> ControllerBuilder<P> controller(final Class<P> type,
                                                                        final Reconciler<P> reconciler) {
            final ControllerBuilder<P> builder = new ControllerBuilder<>(type, reconciler);
            reconciler.configure(builder);
            controllers.add(builder);
            return builder;
        }

        /**
         * @return the operator, not started yet
         */
        public Operator build() {
            return new Operator(this);
        }
    }
}
