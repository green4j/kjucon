/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.github.green4j.kjucon.ControllerObserver.Cause;
import io.github.green4j.kjucon.ControllerObserver.Outcome;
import io.github.green4j.kjucon.ErrorHandler.Phase;
import io.github.green4j.kjucon.util.Errors;
import io.github.green4j.kjucon.util.Metadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Reconciles the resources of one type. Its flow:
 * <ol>
 *   <li>an event of the primary resource, of something it owns or watches, or of an {@link EventSource} queues
 *   a request for it;</li>
 *   <li>a worker takes the request and reads the resource from the cache - a request for a resource which is
 *   gone is dropped;</li>
 *   <li>a resource which is not being deleted gets the finalizer, if it has none yet, and is reconciled;</li>
 *   <li>a resource being deleted which has the finalizer is cleaned up, and the finalizer is removed once the
 *   cleanup succeeds;</li>
 *   <li>a former finalizer of the controller is treated as its finalizer: replaced on a live resource, removed
 *   after the cleanup of one being deleted;</li>
 *   <li>the result decides what follows: nothing, a requeue, or a retry after a back-off.</li>
 * </ol>
 *
 * @param <P> type of the primary resource
 */
public final class Controller<P extends HasMetadata> implements AutoCloseable {
    private static final long POLL_MILLIS = 1_000;
    private static final long SYNC_WAIT_MILLIS = 100;
    private static final long SHUTDOWN_MILLIS = 2_000;
    private static final Duration CONFLICT_RETRY = Duration.ofMillis(200);
    private static final int FINALIZER_ATTEMPTS = 3;

    private final String name;
    private final Class<P> type;
    private final Reconciler<P> reconciler;
    private final int workers;
    private final String finalizer;
    private final List<String> formerFinalizers;
    private final List<String> allFinalizers;
    private final List<String> formerNames;
    private final boolean managedByLabel;
    // the names of the other controllers of the operator
    private volatile Set<String> siblingNames = Set.of();
    private final EventFilter<P> filter;
    private final Map<String, String> selector;
    private final RateLimiter limiter;
    private final long resyncMillis;
    private final List<Watch<?>> watches;
    private final List<EventSource> eventSources;
    private final ControllerObserver observer;
    private final Observed observed;
    private final KubernetesClient client;
    private final InformerRegistry informers;
    private final String labelDomain;

    private final WorkQueue queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger workersAlive = new AtomicInteger();

    private volatile ExecutorService pool;
    private volatile Instant lastSuccess;

    Controller(final String name,
               final Class<P> type,
               final Reconciler<P> reconciler,
               final int workers,
               final String finalizer,
               final List<String> formerFinalizers,
               final List<String> formerNames,
               final boolean managedByLabel,
               final EventFilter<P> filter,
               final Map<String, String> selector,
               final RateLimiter limiter,
               final long resyncMillis,
               final List<Watch<?>> watches,
               final List<EventSource> eventSources,
               final ControllerObserver observer,
               final Observed observed,
               final KubernetesClient client,
               final InformerRegistry informers,
               final String labelDomain) {
        this.name = name;
        this.type = type;
        this.reconciler = reconciler;
        this.workers = workers;
        this.finalizer = finalizer;
        this.formerFinalizers = formerFinalizers;
        final List<String> all = new ArrayList<>(formerFinalizers);
        if (finalizer != null) {
            all.add(finalizer);
        }
        this.allFinalizers = List.copyOf(all);
        this.formerNames = formerNames;
        this.managedByLabel = managedByLabel;
        this.filter = filter;
        this.selector = selector;
        this.limiter = limiter;
        this.resyncMillis = resyncMillis;
        this.watches = watches;
        this.eventSources = eventSources;
        this.observer = observer;
        this.observed = observed;
        this.client = client;
        this.informers = informers;
        this.labelDomain = labelDomain;
        this.queue = new WorkQueue(name);
    }

    /**
     * @return the name of the controller
     */
    public String name() {
        return name;
    }

    /**
     * @return the type of the primary resources
     */
    public Class<P> type() {
        return type;
    }

    /**
     * @return the finalizer the controller holds its resources with, null if it holds none
     */
    public String finalizer() {
        return finalizer;
    }

    /**
     * @return the finalizers the controller used before, which it removes wherever it finds them
     */
    public List<String> formerFinalizers() {
        return formerFinalizers;
    }

    /**
     * @return the names the controller had before, whose objects it takes as its own
     */
    public List<String> formerNames() {
        return formerNames;
    }

    void siblingNames(final Set<String> names) {
        this.siblingNames = Set.copyOf(names);
    }

    /**
     * Queues a reconcile of a resource - for whatever knows of a change Kubernetes does not.
     *
     * @param namespace of the resource, null if it is cluster-scoped
     * @param resourceName of the resource
     */
    public void enqueue(final String namespace,
                        final String resourceName) {
        enqueue(new ReconcileRequest(namespace, resourceName), Cause.EXTERNAL_EVENT);
    }

    void register() {
        informers.register(type, resyncMillis, new PrimaryHandler());
        for (final Watch<?> watch : watches) {
            watch.register(this);
        }
    }

    void start(final BooleanSupplier synced) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(workers, new WorkerThreads());
        pool = executor;
        for (int i = 0; i < workers; i++) {
            executor.execute(() -> work(synced));
        }
        for (final EventSource source : eventSources) {
            source.start(request -> enqueue(request, Cause.EXTERNAL_EVENT));
        }
        observed.by(observer, o -> o.onControllerStarted(name, workers));
    }

    ControllerHealth health() {
        return new ControllerHealth(name, workers, workersAlive.get(), queue.depth(), queue.delayed(),
                limiter.failing(), lastSuccess);
    }

    @Override
    public void close() {
        final boolean wasRunning = running.getAndSet(false);
        for (final EventSource source : eventSources) {
            try {
                source.close();
            } catch (final Exception e) {
                observed.error(name, "An event source failed to close", e);
            }
        }
        queue.close();
        final ExecutorService executor = pool;
        if (executor != null && !Concurrency.shutdown(executor, SHUTDOWN_MILLIS)) {
            observed.error(name, "Workers did not stop in time; left behind", null);
        }
        if (wasRunning) {
            observed.by(observer, o -> o.onControllerStopped(name));
        }
    }

    // once, when the operator is closed: a controller stopped on a lost leadership may still be needed
    void closeReconciler() {
        try {
            reconciler.close();
        } catch (final RuntimeException e) {
            observed.error(name, "The reconciler failed to close", e);
        }
    }

    private void work(final BooleanSupplier synced) {
        workersAlive.incrementAndGet();
        try {
            while (running.get() && !synced.getAsBoolean()) {
                Thread.sleep(SYNC_WAIT_MILLIS);
            }
            while (running.get()) {
                final ReconcileRequest request = queue.poll(POLL_MILLIS);
                if (request == null) {
                    continue;
                }
                observed.by(observer, o -> o.onQueueDepth(name, queue.depth()));
                try {
                    process(request);
                } catch (final RuntimeException e) {
                    observed.error(name, "Unexpected failure processing " + request, e);
                } finally {
                    queue.done(request);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workersAlive.decrementAndGet();
        }
    }

    private void process(final ReconcileRequest request) {
        final P cached = informers.get(type, request.namespace(), request.name());
        if (cached == null || !(isSelected(cached) || isHeldForCleanup(cached))) {
            limiter.forget(request);
            return;
        }
        final int attempt = limiter.failures(request);
        if (Metadata.isBeingDeleted(cached)) {
            if (holdsAny(cached)) {
                cleanup(request, cached, attempt);
            } else {
                limiter.forget(request);
            }
            return;
        }
        reconcile(request, cached, attempt);
    }

    private void reconcile(final ReconcileRequest request,
                           final P cached,
                           final int attempt) {
        observed.by(observer, o -> o.onReconcileStarted(name, request));
        final long started = System.nanoTime();
        ReconcileResult result;
        try {
            P primary = cached;
            if (needsFinalizerPatch(primary)) {
                final boolean adding = finalizer != null && !Finalizers.has(primary, finalizer);
                primary = patchFinalizer(primary, true);
                if (primary != null && adding) {
                    observed.by(observer, o -> o.onFinalizerAdded(name, request));
                }
            }
            result = primary == null
                    ? ReconcileResult.success() // gone meanwhile: nothing to reconcile
                    : Objects.requireNonNull(reconciler.reconcile(context(request, primary, attempt)),
                            "reconcile returned null");
        } catch (final FinalizerConflict e) {
            result = ReconcileResult.requeueAfter(CONFLICT_RETRY);
        } catch (final RuntimeException e) {
            result = ReconcileResult.error(e);
        }
        final Outcome outcome = handle(request, Phase.RECONCILE, result);
        final long duration = System.nanoTime() - started;
        observed.by(observer, o -> o.onReconcileFinished(name, request, outcome, duration));
    }

    private void cleanup(final ReconcileRequest request,
                         final P cached,
                         final int attempt) {
        observed.by(observer, o -> o.onCleanupStarted(name, request));
        final long started = System.nanoTime();
        ReconcileResult result;
        boolean removed = false;
        try {
            final ReconcileContext<P> context = context(request, cached, attempt);
            result = Objects.requireNonNull(reconciler.cleanup(context), "cleanup returned null");
            if (result.isSuccess()) {
                patchFinalizer(context.primary(), false);
                removed = true;
            }
        } catch (final FinalizerConflict e) {
            // the cleanup is done; only the finalizer is left to go, on the next pass
            result = ReconcileResult.requeueAfter(CONFLICT_RETRY);
        } catch (final RuntimeException e) {
            result = ReconcileResult.error(e);
        }
        final Outcome outcome = handle(request, Phase.CLEANUP, result);
        final long duration = System.nanoTime() - started;
        observed.by(observer, o -> o.onCleanupFinished(name, request, outcome, duration));
        if (removed) {
            observed.by(observer, o -> o.onFinalizerRemoved(name, request));
        }
    }

    /**
     * Adds or removes the controller's finalizer, and removes its former ones. The patch holds only while the
     * finalizer list is what it was read as (see {@link Finalizers}); if somebody changed the list meanwhile, the
     * resource is read again and the patch repeated - a few times, then the request is retried quietly. A race for
     * the list is not a failure.
     *
     * @param primary as it was read
     * @param add true to add the finalizer, false to remove it
     * @return the resource as patched, or null if it is gone
     */
    private P patchFinalizer(final P primary,
                             final boolean add) {
        P current = primary;
        for (int attempt = 1; ; attempt++) {
            try {
                return add
                        ? Finalizers.replace(client, current, formerFinalizers, finalizer)
                        : Finalizers.replace(client, current, allFinalizers, null);
            } catch (final RuntimeException e) {
                if (Errors.isNotFound(e)) {
                    return null;
                }
                if (!Finalizers.isConcurrentChange(e)) {
                    throw e;
                }
                if (attempt == FINALIZER_ATTEMPTS) {
                    throw new FinalizerConflict();
                }
            }
            try {
                current = client.resource(primary).get();
            } catch (final RuntimeException e) {
                if (Errors.isNotFound(e)) {
                    return null;
                }
                throw e;
            }
            if (current == null) {
                return null;
            }
        }
    }

    private ReconcileContext<P> context(final ReconcileRequest request,
                                        final P primary,
                                        final int attempt) {
        return new ReconcileContext<>(client, informers, observed, labelDomain, name, formerNames,
                siblingNames, managedByLabel, request, type, primary, attempt);
    }

    private Outcome handle(final ReconcileRequest request,
                           final Phase phase,
                           final ReconcileResult result) {
        if (result.isError()) {
            final Throwable error = result.error();
            if (error instanceof InvalidResourceException) {
                limiter.forget(request);
                report(request, phase, 1, error);
                return Outcome.INVALID;
            }
            final Duration delay = limiter.next(request, Errors.isPermanent(error));
            final int failures = limiter.failures(request);
            report(request, phase, failures, error);
            queue.addAfter(request, delay);
            observed.by(observer, o -> o.onRetryScheduled(name, request, failures, delay));
            return Outcome.ERROR;
        }
        limiter.forget(request);
        if (result.isSuccess()) {
            lastSuccess = Instant.now();
            if (result.after() != null) {
                queue.addAfter(request, result.after());
            }
            return Outcome.SUCCESS;
        }
        if (Duration.ZERO.equals(result.after())) {
            enqueue(request, Cause.REQUEUE);
        } else {
            queue.addAfter(request, result.after());
        }
        return Outcome.REQUEUE;
    }

    private void report(final ReconcileRequest request,
                        final Phase phase,
                        final int attempt,
                        final Throwable error) {
        observed.reconcileError(name, request, phase, attempt, error);
    }

    private void enqueue(final ReconcileRequest request,
                         final Cause cause) {
        if (queue.add(request)) {
            observed.by(observer, o -> o.onEnqueued(name, request, cause));
            observed.by(observer, o -> o.onQueueDepth(name, queue.depth()));
        }
    }

    private boolean isSelected(final P resource) {
        return Metadata.hasLabels(resource, selector);
    }

    // a resource being deleted under the controller's finalizer, current or former, is cleaned up, selected or
    // not: only it can let the resource go
    private boolean isHeldForCleanup(final P resource) {
        return Metadata.isBeingDeleted(resource) && holdsAny(resource);
    }

    private boolean holdsAny(final P resource) {
        for (final String name : allFinalizers) {
            if (Finalizers.has(resource, name)) {
                return true;
            }
        }
        return false;
    }

    // a live resource lacking the finalizer, or holding a former one
    private boolean needsFinalizerPatch(final P resource) {
        if (finalizer != null && !Finalizers.has(resource, finalizer)) {
            return true;
        }
        for (final String former : formerFinalizers) {
            if (Finalizers.has(resource, former)) {
                return true;
            }
        }
        return false;
    }

    private final class PrimaryHandler implements ResourceEventHandler<P> {
        @Override
        public void onAdd(final P resource) {
            if (isSelected(resource) || isHeldForCleanup(resource)) {
                enqueue(ReconcileRequest.of(resource), Cause.PRIMARY_EVENT);
            }
        }

        @Override
        public void onUpdate(final P oldResource,
                             final P newResource) {
            if (!isSelected(newResource) && !isSelected(oldResource) && !isHeldForCleanup(newResource)) {
                return;
            }
            final ReconcileRequest request = ReconcileRequest.of(newResource);
            if (Objects.equals(oldResource.getMetadata().getResourceVersion(),
                    newResource.getMetadata().getResourceVersion())) {
                enqueue(request, Cause.RESYNC);
                return;
            }
            if (Metadata.isBeingDeleted(newResource)
                    || needsFinalizerPatch(newResource)
                    || isSelected(newResource) != isSelected(oldResource)
                    || filter.accept(oldResource, newResource)) {
                enqueue(request, Cause.PRIMARY_EVENT);
            }
        }

        @Override
        public void onDelete(final P resource,
                             final boolean deletedFinalStateUnknown) {
            final ReconcileRequest request = ReconcileRequest.of(resource);
            queue.remove(request);
            limiter.forget(request);
            try {
                reconciler.deleted(request);
            } catch (final RuntimeException e) {
                observed.error(name, "The reconciler failed to forget " + request, e);
            }
        }
    }

    static final class Watch<S extends HasMetadata> {
        private final Class<S> type;
        private final SecondaryMapper<S> mapper;

        Watch(final Class<S> type, final SecondaryMapper<S> mapper) {
            this.type = type;
            this.mapper = mapper;
        }

        private void register(final Controller<?> controller) {
            controller.informers.register(type, 0, new ResourceEventHandler<S>() {
                @Override
                public void onAdd(final S resource) {
                    map(controller, resource);
                }

                @Override
                public void onUpdate(final S oldResource,
                                     final S newResource) {
                    if (!Objects.equals(oldResource.getMetadata().getResourceVersion(),
                            newResource.getMetadata().getResourceVersion())) {
                        map(controller, newResource);
                    }
                }

                @Override
                public void onDelete(final S resource,
                                     final boolean deletedFinalStateUnknown) {
                    map(controller, resource);
                }
            });
        }

        private void map(final Controller<?> controller,
                         final S resource) {
            final Collection<ReconcileRequest> requests;
            try {
                requests = mapper.map(resource);
            } catch (final RuntimeException e) {
                controller.observed.error(controller.name, "Mapping a " + type.getSimpleName() + " failed", e);
                return;
            }
            for (final ReconcileRequest request : requests) {
                controller.enqueue(request, Cause.SECONDARY_EVENT);
            }
        }
    }

    // the finalizer list kept changing under the patch: retried quietly, as a requeue
    private static final class FinalizerConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private FinalizerConflict() {
            super(null, null, false, false);
        }
    }

    private final class WorkerThreads implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable r) {
            final Thread thread = new Thread(r, name + "#worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
