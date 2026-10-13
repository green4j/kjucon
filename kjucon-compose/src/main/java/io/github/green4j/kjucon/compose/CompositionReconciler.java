/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.github.green4j.kjucon.ControllerBuilder;
import io.github.green4j.kjucon.InvalidResourceException;
import io.github.green4j.kjucon.Labels;
import io.github.green4j.kjucon.Ownership;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.ReconcileRequest;
import io.github.green4j.kjucon.ReconcileResult;
import io.github.green4j.kjucon.Reconciler;
import io.github.green4j.kjucon.util.Errors;
import io.github.green4j.kjucon.util.Metadata;
import io.github.green4j.kjucon.util.Names;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Reconciles a primary resource to its {@link Composition}.
 * <p>
 * <b>Reconcile.</b> The components are walked in dependency order. A component is applied - server-side, under the
 * controller's field manager - once every component it depends on is ready; until then it waits, and its object,
 * if there is one, is left as it is. Objects the resource owns which are no longer in the composition are pruned.
 * The readiness of the whole - failed if any component failed, ready if all are, in progress otherwise - goes to
 * the status of the resource. Changes of the objects wake the reconciler up, so nothing is polled; a resync after
 * a while is only a safety net.
 * <p>
 * <b>Cleanup.</b> With {@link CleanupMode#ORDERED}, the objects are deleted in reverse dependency order: an object
 * only once everything which depends on it is gone. The cleanup is repeated until nothing is left, then the
 * finalizer is released.
 * <p>
 * Every type a composition may contain must be declared with {@link Builder#manages}: the controller watches
 * those types, and prune and cleanup look only at them. An object outside the namespace of the resource must be
 * in a namespace the operator watches, so that it is seen to be pruned and deleted; and with
 * {@link CleanupMode#GARBAGE_COLLECTOR}, which relies on owner references, a composition can hold neither such an
 * object nor one to {@link DeletePolicy#ORPHAN orphan}. A composition breaking these rules is invalid: nothing of
 * it is applied, and the resource reports the error.
 * <p>
 * An object which cannot be applied for reasons of its own - it belongs to another resource, or to another
 * controller of this one; a Job would run again - fails its component only: the components which depend on it
 * wait, the others are applied, and what left the composition is pruned. So does an object the API server refuses
 * to apply, under {@link ApplyErrorPolicy#FAIL_COMPONENT}; under {@link ApplyErrorPolicy#STOP_COMPOSITION}, the
 * default, the reconcile stops there.
 *
 * @param <P> type of the primary resource
 */
public final class CompositionReconciler<P extends HasMetadata> implements Reconciler<P> {
    private static final int MAX_STATUS_MESSAGE = 1024;
    private static final String ORPHAN = "true";
    // the key of the readiness of the whole composition among those of its components
    private static final String COMPOSITION = "";

    private final CompositionFactory<P> factory;
    private final List<Class<? extends HasMetadata>> managedTypes;
    private final ReadinessChecks readinessChecks;
    private final ComposedStatusUpdater<P> statusUpdater;
    private final CompositionObserver observer;
    private final Duration resync;
    private final Duration progressResync;
    private final Duration cleanupPoll;
    private final CleanupMode cleanupMode;
    private final ApplyErrorPolicy applyErrors;
    private final long claimWindowNanos;

    // object key -> the resource which applied it last, for the moment before the cache shows the apply
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.nanoTime());

    // what this instance last saw of each resource, for the change notifications
    private final Map<String, Seen> lastReadiness = new ConcurrentHashMap<>();

    private CompositionReconciler(final Builder<P> builder) {
        this.factory = builder.factory;
        this.managedTypes = List.copyOf(builder.managedTypes);
        this.readinessChecks = builder.readinessChecks != null ? builder.readinessChecks : ReadinessChecks.defaults();
        this.statusUpdater = builder.statusUpdater;
        this.observer = builder.observer;
        this.resync = builder.resync;
        this.progressResync = builder.progressResync;
        this.cleanupPoll = builder.cleanupPoll;
        this.cleanupMode = builder.cleanupMode;
        this.applyErrors = builder.applyErrors;
        this.claimWindowNanos = builder.claimWindow.toNanos();
    }

    /**
     * @param factory of the compositions
     * @param <P> type of the primary resource
     * @return a new builder
     */
    public static <P extends HasMetadata> Builder<P> builder(final CompositionFactory<P> factory) {
        return new Builder<>(factory);
    }

    /**
     * @return the types the compositions may contain
     */
    public List<Class<? extends HasMetadata>> managedTypes() {
        return managedTypes;
    }

    /**
     * @return how the objects go away with the resource
     */
    public CleanupMode cleanupMode() {
        return cleanupMode;
    }

    /**
     * Owns every managed type, so a change of any object wakes its resource up, and holds the resources with a
     * finalizer if the cleanup is {@link CleanupMode#ORDERED}. With {@link CleanupMode#GARBAGE_COLLECTOR}, the
     * default finalizer is a former one: resources held with it before the cleanup mode changed are let go.
     *
     * @param controller the builder of the controller
     */
    @Override
    public void configure(final ControllerBuilder<P> controller) {
        for (final Class<? extends HasMetadata> managed : managedTypes) {
            controller.owns(managed);
        }
        if (cleanupMode == CleanupMode.ORDERED) {
            controller.finalizer(true);
        } else {
            controller.finalizer(false).formerDefaultFinalizer();
        }
    }

    @Override
    public void close() {
        claims.clear();
        lastReadiness.clear();
    }

    @Override
    public ReconcileResult reconcile(final ReconcileContext<P> context) {
        try {
            final Composition<P> composition = factory.create(context);
            validate(context, composition);
            final Map<Component<P, ?>, Readiness> readiness = new IdentityHashMap<>();
            final List<RuntimeException> failures = new ArrayList<>();
            final Set<String> desiredKeys = applyAll(context, composition, readiness, failures);
            prune(context, desiredKeys);
            final Readiness aggregate = aggregate(composition, readiness);
            statusUpdater.update(context, aggregate);
            notifyComposition(context, aggregate);
            if (!failures.isEmpty()) {
                // an object somebody else holds, or one the server refused: nothing wakes the resource up when that
                // changes, so it is retried with back-off
                final RuntimeException first = failures.get(0);
                for (int i = 1; i < failures.size(); i++) {
                    first.addSuppressed(failures.get(i));
                }
                return ReconcileResult.error(first);
            }
            return ReconcileResult.success(aggregate.isReady() ? resync : progressResync);
        } catch (final InvalidResourceException e) {
            reportFailure(context, e);
            throw e;
        } catch (final RuntimeException e) {
            reportFailure(context, e);
            return ReconcileResult.error(e);
        }
    }

    @Override
    public ReconcileResult cleanup(final ReconcileContext<P> context) {
        try {
            statusUpdater.update(context, Readiness.terminating());
        } catch (final RuntimeException e) {
            context.reportError("Cannot set the Deleting status", e);
        }
        Composition<P> composition = null;
        try {
            composition = factory.create(context);
        } catch (final RuntimeException e) {
            // an invalid resource can still be deleted: everything it owns goes, in no particular order
            context.reportError("Cannot compose the resource for its cleanup; deleting what it owns unordered", e);
        }
        boolean remaining = false;
        final Set<String> composedKeys = new HashSet<>();
        if (composition != null) {
            remaining = deleteInOrder(context, composition, composedKeys);
        }
        for (final Class<? extends HasMetadata> type : managedTypes) {
            for (final HasMetadata object : owned(context, type)) {
                if (composedKeys.contains(Component.keyOf(object))) {
                    continue;
                }
                remaining = true;
                if (!Metadata.isBeingDeleted(object)) {
                    removeOrRelease(context, object,
                            () -> notify(context, o -> o.onDeleted(context.request(), object)));
                }
            }
        }
        if (remaining) {
            return ReconcileResult.requeueAfter(cleanupPoll);
        }
        return ReconcileResult.success();
    }

    @Override
    public void deleted(final ReconcileRequest request) {
        lastReadiness.remove(request.key());
        claims.values().removeIf(claim -> claim.resource.equals(request.key()));
    }

    private void validate(final ReconcileContext<P> context,
                          final Composition<P> composition) {
        final String primaryNamespace = context.namespace();
        for (final Component<P, ?> component : composition.components()) {
            if (!managedTypes.contains(component.type())) {
                throw new InvalidResourceException(component + ": " + component.type().getSimpleName()
                        + " is not among the managed types of the composition reconciler");
            }
            final String namespace = component.namespaceFor(primaryNamespace);
            if (context.isInformed(component.type(), primaryNamespace)
                    && !context.isInformed(component.type(), namespace)) {
                throw new InvalidResourceException(component + ": namespace " + namespace
                        + " is not watched by the operator, so the object could be neither pruned nor deleted;"
                        + " watch that namespace too, or all of them");
            }
            if (cleanupMode == CleanupMode.GARBAGE_COLLECTOR) {
                if (component.deletePolicy() == DeletePolicy.ORPHAN) {
                    throw new InvalidResourceException(component + ": DeletePolicy.ORPHAN needs CleanupMode.ORDERED;"
                            + " the garbage collector would delete the object with its owner");
                }
                if (primaryNamespace != null && !primaryNamespace.equals(namespace)) {
                    throw new InvalidResourceException(component + ": an object outside the namespace of the"
                            + " resource has no owner reference, so the garbage collector would never delete it;"
                            + " it needs CleanupMode.ORDERED");
                }
            }
        }
    }

    private Set<String> applyAll(final ReconcileContext<P> context,
                                 final Composition<P> composition,
                                 final Map<Component<P, ?>, Readiness> readiness,
                                 final List<RuntimeException> failures) {
        final Set<String> desiredKeys = new HashSet<>();
        final Map<Component<P, ?>, HasMetadata> desired = new IdentityHashMap<>();
        for (final Component<P, ?> component : composition.components()) {
            desiredKeys.add(component.key(context.namespace()));
            final List<Component<?, ?>> blockedBy = new ArrayList<>();
            for (final Component<P, ?> dependency : component.dependencies()) {
                if (!readiness.get(dependency).isReady()) {
                    blockedBy.add(dependency);
                }
            }
            final Readiness current;
            if (blockedBy.isEmpty()) {
                current = applyAndCheck(context, composition, component, desired, failures);
            } else {
                notify(context, o -> o.onComponentWaiting(context.request(), component, blockedBy));
                current = Readiness.inProgress("waiting for " + blockedBy);
            }
            readiness.put(component, current);
            notifyComponent(context, component, current);
        }
        return desiredKeys;
    }

    private <R extends HasMetadata> Readiness applyAndCheck(final ReconcileContext<P> context,
                                                            final Composition<P> composition,
                                                            final Component<P, R> component,
                                                            final Map<Component<P, ?>, HasMetadata> desired,
                                                            final List<RuntimeException> failures) {
        final long started = System.nanoTime();
        final HasMetadata applied;
        try {
            applied = apply(context, composition, component, desired);
        } catch (final NotAppliedException e) {
            if (e.failure != null) {
                failures.add(e.failure);
            }
            return e.readiness;
        }
        final long duration = System.nanoTime() - started;
        notify(context, o -> o.onComponentApplied(context.request(), component, duration));
        return check(component, applied);
    }

    private <R extends HasMetadata> R apply(final ReconcileContext<P> context,
                                            final Composition<P> composition,
                                            final Component<P, R> component,
                                            final Map<Component<P, ?>, HasMetadata> desiredByComponent) {
        final ObjectMeta metadata = context.newMetadata(component.namespaceFor(context.namespace()), component.name());
        if (Names.isLabelValue(component.name())) {
            metadata.getLabels().put(Labels.COMPONENT, component.name());
        }
        if (component.deletePolicy() == DeletePolicy.ORPHAN) {
            metadata.getAnnotations().put(orphanAnnotation(context), ORPHAN);
        }
        composition.decorate(context, component, metadata);
        final R desired = Objects.requireNonNull(component.factory().desired(context, metadata),
                () -> "The factory of " + component + " returned null");
        final String expected = component.key(context.namespace());
        final String built = Component.keyOf(desired);
        if (!expected.equals(built)) {
            throw new IllegalStateException("The factory of " + component
                    + " must build the object with the type and metadata it is given: " + expected + ", not " + built);
        }
        if (desired instanceof Job && ((Job) desired).getSpec() != null
                && ((Job) desired).getSpec().getTtlSecondsAfterFinished() != null) {
            throw new NotAppliedException(null, Readiness.failed("a composed Job deleted by its TTL would be"
                    + " created and run again; leave ttlSecondsAfterFinished out"));
        }
        final HasMetadata existing = context.get(component.type(), component.namespaceFor(context.namespace()),
                component.name());
        final Ownership ownership = existing == null ? null : context.ownershipOf(existing);
        claim(context, component, existing, ownership);
        hold(context, component, expected);
        if (!component.rolloutSources().isEmpty()) {
            final List<HasMetadata> sources = new ArrayList<>();
            for (final Component<P, ?> source : component.rolloutSources()) {
                sources.add(desiredByComponent.get(source));
            }
            Rollouts.stamp(desired, sources, context.labelDomain(), context.client().getKubernetesSerialization());
        }
        desiredByComponent.put(component, desired);
        final boolean adopting = ownership == Ownership.UNOWNED;
        if (adopting) {
            // applied only over the object as it was seen, so that one somebody else has just taken is not taken
            // from them
            desired.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
        }
        try {
            return context.apply(desired);
        } catch (final RuntimeException e) {
            if (adopting && Errors.isConflict(e)) {
                final String message = "the object changed while being adopted";
                throw new NotAppliedException(new IllegalStateException(component + ": " + message),
                        Readiness.inProgress(message));
            }
            if (Errors.isApiError(e) && policyOf(component) == ApplyErrorPolicy.FAIL_COMPONENT) {
                throw new NotAppliedException(e, Readiness.failed(Errors.message(e, MAX_STATUS_MESSAGE)));
            }
            throw e;
        } finally {
            if (adopting) {
                desired.getMetadata().setResourceVersion(null);
            }
        }
    }

    private ApplyErrorPolicy policyOf(final Component<P, ?> component) {
        return component.applyErrors() != null ? component.applyErrors() : applyErrors;
    }

    private static NotAppliedException contested(final Component<?, ?> component,
                                                  final String message) {
        return new NotAppliedException(new IllegalStateException(component + ": " + message),
                Readiness.failed(message));
    }

    /**
     * Refuses to apply over an object which belongs to another resource, or to another controller of this one. In
     * the namespace of the resource the API server would refuse a second controller reference anyway; elsewhere,
     * and for a cluster-scoped object, nothing else would, and two resources would take the object from each
     * other - and the cleanup of one would delete it under the other. An object nobody owns is adopted.
     *
     * @param context of the primary resource
     * @param component of the object
     * @param existing object of the component, null if there is none
     * @param ownership of the existing object, null if there is none
     */
    private static void claim(final ReconcileContext<?> context,
                              final Component<?, ?> component,
                              final HasMetadata existing,
                              final Ownership ownership) {
        if (ownership == Ownership.OTHER_RESOURCE) {
            final String domain = context.labelDomain();
            final Map<String, String> annotations = existing.getMetadata().getAnnotations();
            final String owner = annotations == null ? null : annotations.get(Labels.owner(domain));
            final String ownerKind = annotations == null ? null : annotations.get(Labels.ownerKind(domain));
            throw contested(component, "the object belongs to "
                    + (owner == null ? "the resource of uid " + existing.getMetadata().getLabels().get(
                            Labels.ownerUid(domain)) : (ownerKind == null ? "" : ownerKind + ' ') + owner)
                    + "; an object can belong to one resource only");
        }
        if (ownership == Ownership.OTHER_CONTROLLER) {
            final Map<String, String> labels = existing.getMetadata().getLabels();
            final String controller = labels.getOrDefault(Labels.controller(context.labelDomain()),
                    labels.get(Labels.MANAGED_BY));
            throw contested(component, "the object is applied by controller " + controller
                    + " of the same resource; an object can have one controller only");
        }
    }

    /**
     * Holds the object for the resource until the cache shows the apply: another reconcile of this instance -
     * with more than one worker - must not apply it for another resource meanwhile, seeing nobody's object, or no
     * object, in a cache which has not caught up yet.
     *
     * @param context of the primary resource
     * @param component of the object
     * @param key of the object
     */
    private void hold(final ReconcileContext<P> context,
                      final Component<P, ?> component,
                      final String key) {
        final String uid = context.primary().getMetadata().getUid();
        final long now = System.nanoTime();
        sweep(now);
        final Claim[] held = new Claim[1];
        claims.compute(key, (k, current) -> {
            if (current != null && !current.uid.equals(uid) && now - current.at < claimWindowNanos) {
                held[0] = current;
                return current;
            }
            return new Claim(uid, context.request().key(), now);
        });
        if (held[0] != null) {
            throw contested(component, "the object is being applied for the resource of uid " + held[0].uid
                    + "; an object can belong to one resource only");
        }
    }

    private void sweep(final long now) {
        final long last = lastSweep.get();
        if (now - last < claimWindowNanos || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        claims.values().removeIf(claim -> now - claim.at >= claimWindowNanos);
    }

    @SuppressWarnings("unchecked")
    private <R extends HasMetadata> Readiness check(final Component<P, R> component,
                                                    final HasMetadata object) {
        final ReadinessCheck<? super R> custom = component.readiness();
        if (custom == null || object == null || Metadata.isBeingDeleted(object)) {
            return readinessChecks.check(object);
        }
        return custom.check((R) object);
    }

    private void prune(final ReconcileContext<P> context,
                       final Set<String> desiredKeys) {
        for (final Class<? extends HasMetadata> type : managedTypes) {
            for (final HasMetadata object : owned(context, type)) {
                if (desiredKeys.contains(Component.keyOf(object)) || Metadata.isBeingDeleted(object)) {
                    continue;
                }
                removeOrRelease(context, object, () -> notify(context, o -> o.onPruned(context.request(), object)));
            }
        }
    }

    private boolean deleteInOrder(final ReconcileContext<P> context,
                                  final Composition<P> composition,
                                  final Set<String> composedKeys) {
        final Map<Component<P, ?>, HasMetadata> present = new IdentityHashMap<>();
        for (final Component<P, ?> component : composition.components()) {
            composedKeys.add(component.key(context.namespace()));
            final HasMetadata object = context.get(component.type(), component.namespaceFor(context.namespace()),
                    component.name());
            if (object != null && isOurs(context, object)) {
                present.put(component, object);
            }
        }
        final List<Component<P, ?>> reversed = new ArrayList<>(composition.components());
        Collections.reverse(reversed);
        for (final Component<P, ?> component : reversed) {
            final HasMetadata object = present.get(component);
            if (object == null || Metadata.isBeingDeleted(object)) {
                continue;
            }
            boolean dependentsGone = true;
            for (final Component<P, ?> dependent : composition.dependentsOf(component)) {
                if (present.containsKey(dependent)) {
                    dependentsGone = false;
                    break;
                }
            }
            if (dependentsGone && removeOrRelease(context, object,
                    () -> notify(context, o -> o.onDeleted(context.request(), object)))) {
                present.remove(component);
            }
        }
        return !present.isEmpty();
    }

    /**
     * Deletes the object, or releases it if its policy says so.
     *
     * @param context of the primary resource
     * @param object to delete or release
     * @param onDeleted run after a deletion
     * @return true if it was released - it is no longer the composition's to wait for
     */
    private boolean removeOrRelease(final ReconcileContext<P> context,
                                    final HasMetadata object,
                                    final Runnable onDeleted) {
        final Map<String, String> annotations = object.getMetadata().getAnnotations();
        if (annotations != null && ORPHAN.equals(annotations.get(orphanAnnotation(context)))) {
            release(context, object);
            unhold(context, object);
            notify(context, o -> o.onOrphaned(context.request(), object));
            return true;
        }
        try {
            context.delete(object);
        } catch (final RuntimeException e) {
            if (!Errors.isNotFound(e)) {
                throw e;
            }
        }
        unhold(context, object);
        onDeleted.run();
        return false;
    }

    // the object is let go: another resource may have it at once
    private void unhold(final ReconcileContext<P> context,
                        final HasMetadata object) {
        final String uid = context.primary().getMetadata().getUid();
        claims.computeIfPresent(Component.keyOf(object), (key, claim) -> claim.uid.equals(uid) ? null : claim);
    }

    private void release(final ReconcileContext<P> context,
                         final HasMetadata object) {
        final String ownerUid = context.primary().getMetadata().getUid();
        final String domain = context.labelDomain();
        context.client().resource(object).edit(o -> {
            final ObjectMeta meta = o.getMetadata();
            if (meta.getLabels() != null) {
                final Map<String, String> labels = new HashMap<>(meta.getLabels());
                labels.remove(Labels.ownerUid(domain));
                labels.remove(Labels.controller(domain));
                // the others only if they are as the controller set them: another tool may have set them
                if (context.isOwnName(labels.get(Labels.MANAGED_BY))) {
                    labels.remove(Labels.MANAGED_BY);
                }
                labels.remove(Labels.PART_OF, context.primary().getMetadata().getName());
                labels.remove(Labels.COMPONENT, meta.getName());
                meta.setLabels(labels);
            }
            if (meta.getAnnotations() != null) {
                final Map<String, String> remaining = new HashMap<>(meta.getAnnotations());
                remaining.remove(Labels.owner(domain));
                remaining.remove(Labels.ownerKind(domain));
                remaining.remove(orphanAnnotation(context));
                meta.setAnnotations(remaining);
            }
            if (meta.getOwnerReferences() != null) {
                final List<OwnerReference> references = new ArrayList<>();
                for (final OwnerReference reference : meta.getOwnerReferences()) {
                    if (!ownerUid.equals(reference.getUid())) {
                        references.add(reference);
                    }
                }
                meta.setOwnerReferences(references);
            }
            return o;
        });
    }

    private List<HasMetadata> owned(final ReconcileContext<P> context,
                                    final Class<? extends HasMetadata> type) {
        final List<HasMetadata> result = new ArrayList<>();
        for (final HasMetadata object : context.owned(type)) {
            if (isOurs(context, object)) {
                result.add(object);
            }
        }
        return result;
    }

    // owned by the resource and applied by this controller, under its name or a former one - another controller
    // may compose the same resource
    private static boolean isOurs(final ReconcileContext<?> context,
                                  final HasMetadata object) {
        return context.ownershipOf(object) == Ownership.OURS;
    }

    private static String orphanAnnotation(final ReconcileContext<?> context) {
        return context.labelDomain() + "/delete-policy-orphan";
    }

    private Readiness aggregate(final Composition<P> composition,
                                final Map<Component<P, ?>, Readiness> readiness) {
        Readiness firstPending = null;
        for (final Component<P, ?> component : composition.components()) {
            final Readiness current = readiness.get(component);
            if (current.state() == Readiness.State.FAILED) {
                return Readiness.failed(component + ": " + current.message());
            }
            if (!current.isReady() && firstPending == null) {
                firstPending = Readiness.inProgress(current.message() == null
                        ? component + ": " + current.state() : component + ": " + current.message());
            }
        }
        return firstPending != null ? firstPending : Readiness.ready();
    }

    private void reportFailure(final ReconcileContext<P> context,
                               final RuntimeException error) {
        try {
            statusUpdater.update(context, Readiness.failed(Errors.message(error, MAX_STATUS_MESSAGE)));
        } catch (final RuntimeException e) {
            context.reportError("Cannot set the Error status", e);
        }
    }

    private void notifyComponent(final ReconcileContext<P> context,
                                 final Component<P, ?> component,
                                 final Readiness current) {
        final Readiness previous = seen(context).put(component.key(context.namespace()), current);
        if (!current.equals(previous)) {
            notify(context, o -> o.onComponentReadinessChanged(context.request(), component, previous, current));
        }
    }

    private void notifyComposition(final ReconcileContext<P> context,
                                   final Readiness current) {
        final Readiness previous = seen(context).put(COMPOSITION, current);
        if (!current.equals(previous)) {
            notify(context, o -> o.onCompositionReadinessChanged(context.request(), previous, current));
        }
    }

    // by uid: a reconcile of a deleted resource still running may remember it after it is forgotten, and a new
    // resource of the same name must not inherit that
    private Map<String, Readiness> seen(final ReconcileContext<P> context) {
        final String uid = context.primary().getMetadata().getUid();
        return lastReadiness.compute(context.request().key(),
                (key, seen) -> seen != null && seen.uid.equals(uid) ? seen : new Seen(uid)).readiness;
    }

    private void notify(final ReconcileContext<P> context,
                        final Consumer<CompositionObserver> event) {
        if (observer == null) {
            return;
        }
        try {
            event.accept(observer);
        } catch (final RuntimeException e) {
            context.reportError("A composition observer failed", e);
        }
    }

    /**
     * Configures a {@link CompositionReconciler}.
     *
     * @param <P> type of the primary resource
     */
    public static final class Builder<P extends HasMetadata> {
        private static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);
        private static final Duration DEFAULT_PROGRESS_RESYNC = Duration.ofSeconds(30);
        private static final Duration DEFAULT_CLEANUP_POLL = Duration.ofSeconds(2);
        // longer than a cache takes to show an apply, by far
        private static final Duration DEFAULT_CLAIM_WINDOW = Duration.ofMinutes(1);

        private final CompositionFactory<P> factory;
        private final Set<Class<? extends HasMetadata>> managedTypes = new LinkedHashSet<>();
        private ReadinessChecks readinessChecks;
        private ComposedStatusUpdater<P> statusUpdater = ComposedStatusUpdater.standard();
        private CompositionObserver observer;
        private Duration resync = DEFAULT_RESYNC;
        private Duration progressResync = DEFAULT_PROGRESS_RESYNC;
        private Duration cleanupPoll = DEFAULT_CLEANUP_POLL;
        private CleanupMode cleanupMode = CleanupMode.ORDERED;
        private ApplyErrorPolicy applyErrors = ApplyErrorPolicy.STOP_COMPOSITION;
        private Duration claimWindow = DEFAULT_CLAIM_WINDOW;

        private Builder(final CompositionFactory<P> factory) {
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        /**
         * @param types the compositions may contain
         * @return this
         */
        @SafeVarargs
        public final Builder<P> manages(final Class<? extends HasMetadata>... types) {
            for (final Class<? extends HasMetadata> type : types) {
                managedTypes.add(Objects.requireNonNull(type, "type"));
            }
            return this;
        }

        /**
         * @param checks of the readiness of objects; {@link ReadinessChecks#defaults()} by default
         * @return this
         */
        public Builder<P> readiness(final ReadinessChecks checks) {
            this.readinessChecks = Objects.requireNonNull(checks, "checks");
            return this;
        }

        /**
         * @param updater of the status of the resource; {@link ComposedStatusUpdater#standard()} by default
         * @return this
         */
        public Builder<P> statusUpdater(final ComposedStatusUpdater<P> updater) {
            this.statusUpdater = Objects.requireNonNull(updater, "statusUpdater");
            return this;
        }

        /**
         * @param compositionObserver of what the reconciler does
         * @return this
         */
        public Builder<P> observer(final CompositionObserver compositionObserver) {
            this.observer = compositionObserver;
            return this;
        }

        /**
         * @param period after which a ready composition is reconciled again anyway; 5 minutes by default
         * @return this
         */
        public Builder<P> resync(final Duration period) {
            this.resync = Objects.requireNonNull(period, "resync");
            return this;
        }

        /**
         * @param period after which a composition not ready yet is reconciled again, should no event come; 30
         *               seconds by default
         * @return this
         */
        public Builder<P> progressResync(final Duration period) {
            this.progressResync = Objects.requireNonNull(period, "progressResync");
            return this;
        }

        /**
         * @param period between the passes of a cleanup waiting for objects to disappear; 2 seconds by default
         * @return this
         */
        public Builder<P> cleanupPoll(final Duration period) {
            this.cleanupPoll = Objects.requireNonNull(period, "cleanupPoll");
            return this;
        }

        /**
         * @param mode of the cleanup; {@link CleanupMode#ORDERED} by default
         * @return this
         */
        public Builder<P> cleanup(final CleanupMode mode) {
            this.cleanupMode = Objects.requireNonNull(mode, "cleanup");
            return this;
        }

        /**
         * @param policy when the API server refuses to apply an object, for the components which set none;
         *               {@link ApplyErrorPolicy#STOP_COMPOSITION} by default
         * @return this
         */
        public Builder<P> applyErrors(final ApplyErrorPolicy policy) {
            this.applyErrors = Objects.requireNonNull(policy, "applyErrors");
            return this;
        }

        // how long an apply holds its object for the resource against the other reconciles of this instance
        Builder<P> claimWindow(final Duration window) {
            this.claimWindow = Objects.requireNonNull(window, "claimWindow");
            return this;
        }

        /**
         * @return the reconciler
         */
        public CompositionReconciler<P> build() {
            if (managedTypes.isEmpty()) {
                throw new IllegalStateException("No managed types: declare what the compositions contain");
            }
            return new CompositionReconciler<>(this);
        }
    }

    private static final class Seen {
        private final String uid;
        private final Map<String, Readiness> readiness = new ConcurrentHashMap<>();

        private Seen(final String uid) {
            this.uid = uid;
        }
    }

    private static final class Claim {
        private final String uid;
        private final String resource;
        private final long at;

        private Claim(final String uid,
                      final String resource,
                      final long at) {
            this.uid = uid;
            this.resource = resource;
            this.at = at;
        }
    }

    // a component not applied this time, for a reason of its own object
    private static final class NotAppliedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        // what the reconcile fails with, to be retried with back-off - the object is somebody else's or being taken,
        // and nothing wakes the resource up when it is free; or the server refused it; null if the status says it all
        private final RuntimeException failure;
        private final transient Readiness readiness;

        private NotAppliedException(final RuntimeException failure,
                                    final Readiness readiness) {
            super(readiness.message(), null, false, false);
            this.failure = failure;
            this.readiness = readiness;
        }
    }
}
