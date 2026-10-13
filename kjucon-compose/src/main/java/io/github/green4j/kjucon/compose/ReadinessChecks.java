/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetStatus;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.api.model.apps.StatefulSetUpdateStrategy;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.github.green4j.kjucon.status.Conditions;
import io.github.green4j.kjucon.status.StandardStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The readiness checks of a composition, by type. Without a check of its own, a type falls back to the generic
 * one, which reads it the way kstatus does:
 * <ul>
 *   <li>being deleted: {@link Readiness.State#TERMINATING};</li>
 *   <li>a {@link CustomResource} declaring a {@link StandardStatus}: its phase, provided the status observed the
 *   current generation, and in progress while it has no status at all - this is how a parent sees its
 *   kjucon-managed children;</li>
 *   <li>a status with {@code observedGeneration} behind {@code metadata.generation}: in progress;</li>
 *   <li>a status with conditions: {@code Stalled}/{@code Failed} True fails it, {@code Ready} decides, then
 *   {@code Reconciling} True keeps it in progress;</li>
 *   <li>anything else - a ConfigMap, a Secret, a ServiceAccount, a resource without a status - is ready once it
 *   exists.</li>
 * </ul>
 * Built in on top: Deployment, StatefulSet, DaemonSet, ReplicaSet, Job, Pod, PersistentVolumeClaim and Service
 * of type LoadBalancer. An Ingress is ready once it exists, as kstatus has it; {@link #INGRESS_WITH_ADDRESS}
 * waits for an address instead.
 */
public final class ReadinessChecks {
    /** An Ingress is ready once its load balancer reports an address. */
    public static final ReadinessCheck<Ingress> INGRESS_WITH_ADDRESS = ingress ->
            ingress.getStatus() != null
                    && ingress.getStatus().getLoadBalancer() != null
                    && ingress.getStatus().getLoadBalancer().getIngress() != null
                    && !ingress.getStatus().getLoadBalancer().getIngress().isEmpty()
                    ? Readiness.ready() : Readiness.inProgress("waiting for an address");

    // what the status of an object says, as kstatus reads it
    private static final String STATUS = "status";
    private static final String CONDITIONS = "conditions";
    private static final String OBSERVED_GENERATION = "observedGeneration";
    private static final String TYPE = "type";
    private static final String MESSAGE = "message";
    // the words of the Kubernetes API the built-in checks go by
    private static final String PROGRESSING = "Progressing";
    private static final String PROGRESS_DEADLINE_EXCEEDED = "ProgressDeadlineExceeded";
    private static final String ON_DELETE = "OnDelete";
    private static final String COMPLETE = "Complete";
    private static final String SUCCEEDED = "Succeeded";
    private static final String BOUND = "Bound";
    private static final String LOST = "Lost";
    private static final String LOAD_BALANCER = "LoadBalancer";
    // a condition type of kstatus and of a Job, and a phase of a Pod
    private static final String FAILED = "Failed";
    // what a workload without a status is waiting for: its controller to see it
    private static final String NO_STATUS = "no status yet";
    private static final String GENERATION_NOT_OBSERVED = "generation not observed yet";
    // what a Pod or a claim without a phase yet is
    private static final String PENDING = "pending";
    // the replicas of a workload which does not say
    private static final int DEFAULT_REPLICAS = 1;

    private final Map<Class<?>, ReadinessCheck<?>> checks = new ConcurrentHashMap<>();
    private final KubernetesSerialization serialization;

    private ReadinessChecks(final KubernetesSerialization serialization) {
        this.serialization = serialization;
    }

    /**
     * @return a new registry with the built-in checks
     */
    public static ReadinessChecks defaults() {
        final ReadinessChecks result = new ReadinessChecks(new KubernetesSerialization());
        result.with(Deployment.class, ReadinessChecks::deployment);
        result.with(StatefulSet.class, ReadinessChecks::statefulSet);
        result.with(DaemonSet.class, ReadinessChecks::daemonSet);
        result.with(ReplicaSet.class, ReadinessChecks::replicaSet);
        result.with(Job.class, ReadinessChecks::job);
        result.with(Pod.class, ReadinessChecks::pod);
        result.with(PersistentVolumeClaim.class, ReadinessChecks::persistentVolumeClaim);
        result.with(Service.class, ReadinessChecks::service);
        return result;
    }

    /**
     * Sets the check of a type, replacing a built-in one.
     *
     * @param type checked
     * @param check of it
     * @param <R> the type
     * @return this
     */
    public <R extends HasMetadata> ReadinessChecks with(final Class<R> type,
                                                        final ReadinessCheck<? super R> check) {
        checks.put(type, Objects.requireNonNull(check, "check"));
        return this;
    }

    /**
     * @param resource to check, may be null
     * @return its readiness: {@link Readiness#notFound()} for null, {@link Readiness#terminating()} for one being
     *         deleted, otherwise what its check says
     */
    @SuppressWarnings("unchecked")
    public Readiness check(final HasMetadata resource) {
        if (resource == null) {
            return Readiness.notFound();
        }
        if (resource.getMetadata().getDeletionTimestamp() != null) {
            return Readiness.terminating();
        }
        final ReadinessCheck<HasMetadata> check = (ReadinessCheck<HasMetadata>) checks.get(resource.getClass());
        return check != null ? check.check(resource) : generic(resource);
    }

    /**
     * The generic check, as described above.
     *
     * @param resource to check
     * @return its readiness
     */
    @SuppressWarnings("unchecked")
    public Readiness generic(final HasMetadata resource) {
        final Long generation = resource.getMetadata().getGeneration();
        final Object customStatus = resource instanceof CustomResource
                ? ((CustomResource<?, ?>) resource).getStatus() : null;
        if (customStatus instanceof StandardStatus) {
            return standardStatus((StandardStatus) customStatus, generation);
        }
        if (customStatus == null && StandardStatuses.isDeclaredBy(resource.getClass())) {
            // its controller reports a standard status, and has not yet: it has not reconciled it
            return Readiness.inProgress("not reconciled yet");
        }
        final Map<String, Object> map = serialization.convertValue(resource, Map.class);
        final Object status = map.get(STATUS);
        if (!(status instanceof Map)) {
            return Readiness.ready();
        }
        final Map<String, Object> statusMap = (Map<String, Object>) status;
        final Object observed = statusMap.get(OBSERVED_GENERATION);
        if (observed instanceof Number && behind(generation, ((Number) observed).longValue())) {
            return Readiness.inProgress(GENERATION_NOT_OBSERVED);
        }
        final Object conditions = statusMap.get(CONDITIONS);
        if (!(conditions instanceof List)) {
            return Readiness.ready();
        }
        String ready = null;
        String readyMessage = null;
        boolean reconciling = false;
        for (final Object item : (List<Object>) conditions) {
            if (!(item instanceof Map)) {
                continue;
            }
            final Map<String, Object> condition = (Map<String, Object>) item;
            final Object type = condition.get(TYPE);
            final Object message = condition.get(MESSAGE);
            final boolean isTrue = Conditions.TRUE.equals(condition.get(STATUS));
            if ((Conditions.STALLED.equals(type) || FAILED.equals(type)) && isTrue) {
                return Readiness.failed(String.valueOf(message));
            }
            if (Conditions.READY.equals(type)) {
                ready = String.valueOf(condition.get(STATUS));
                readyMessage = message != null ? String.valueOf(message) : null;
            }
            if (Conditions.RECONCILING.equals(type) && isTrue) {
                reconciling = true;
            }
        }
        if (ready != null) {
            return Conditions.TRUE.equals(ready) ? Readiness.ready()
                    : Readiness.inProgress(readyMessage != null ? readyMessage : "not Ready");
        }
        return reconciling ? Readiness.inProgress("reconciling") : Readiness.ready();
    }

    static Readiness standardStatus(final StandardStatus status,
                                    final Long generation) {
        if (behind(generation, status.getObservedGeneration())) {
            return Readiness.inProgress(GENERATION_NOT_OBSERVED);
        }
        if (status.hasPhase(StandardStatus.READY)) {
            return Readiness.ready();
        }
        if (status.hasPhase(StandardStatus.ERROR)) {
            return Readiness.failed(status.getMessage());
        }
        return Readiness.inProgress(status.getMessage() != null ? status.getMessage() : status.getPhase());
    }

    static Readiness deployment(final Deployment deployment) {
        final DeploymentStatus status = deployment.getStatus();
        final Readiness unobserved = unobserved(deployment, status == null ? null : status.getObservedGeneration(),
                status != null);
        if (unobserved != null) {
            return unobserved;
        }
        if (status.getConditions() != null) {
            for (final DeploymentCondition condition : status.getConditions()) {
                if (PROGRESSING.equals(condition.getType())
                        && PROGRESS_DEADLINE_EXCEEDED.equals(condition.getReason())) {
                    return Readiness.failed(condition.getMessage());
                }
            }
        }
        final int replicas = orDefault(deployment.getSpec().getReplicas(), DEFAULT_REPLICAS);
        final int updated = orDefault(status.getUpdatedReplicas(), 0);
        final int ready = orDefault(status.getReadyReplicas(), 0);
        final int available = orDefault(status.getAvailableReplicas(), 0);
        final int total = orDefault(status.getReplicas(), 0);
        if (updated < replicas) {
            return Readiness.inProgress(updated + "/" + replicas + " replicas updated");
        }
        if (total > updated) {
            return Readiness.inProgress((total - updated) + " old replicas pending termination");
        }
        if (ready < replicas || available < replicas) {
            return Readiness.inProgress(Math.min(ready, available) + "/" + replicas + " replicas available");
        }
        return Readiness.ready();
    }

    static Readiness statefulSet(final StatefulSet statefulSet) {
        final StatefulSetStatus status = statefulSet.getStatus();
        final Readiness unobserved = unobserved(statefulSet, status == null ? null : status.getObservedGeneration(),
                status != null);
        if (unobserved != null) {
            return unobserved;
        }
        final int replicas = orDefault(statefulSet.getSpec().getReplicas(), DEFAULT_REPLICAS);
        final int ready = orDefault(status.getReadyReplicas(), 0);
        if (ready < replicas) {
            return Readiness.inProgress(ready + "/" + replicas + " replicas ready");
        }
        final StatefulSetUpdateStrategy strategy = statefulSet.getSpec().getUpdateStrategy();
        if (strategy != null && ON_DELETE.equals(strategy.getType())) {
            // pods are replaced only when somebody deletes them: the revisions may differ for good
            return Readiness.ready();
        }
        final Integer partition = strategy != null && strategy.getRollingUpdate() != null
                ? strategy.getRollingUpdate().getPartition() : null;
        if (partition != null && partition > 0) {
            // only the pods from the partition up are updated: the current revision never catches up
            final int expected = Math.max(0, replicas - partition);
            final int updated = orDefault(status.getUpdatedReplicas(), 0);
            return updated < expected
                    ? Readiness.inProgress(updated + "/" + expected + " replicas updated")
                    : Readiness.ready();
        }
        final String updateRevision = status.getUpdateRevision();
        if (updateRevision != null && !updateRevision.equals(status.getCurrentRevision())) {
            return Readiness.inProgress("rolling out revision " + updateRevision);
        }
        return Readiness.ready();
    }

    static Readiness daemonSet(final DaemonSet daemonSet) {
        final DaemonSetStatus status = daemonSet.getStatus();
        final Readiness unobserved = unobserved(daemonSet, status == null ? null : status.getObservedGeneration(),
                status != null);
        if (unobserved != null) {
            return unobserved;
        }
        final int desired = orDefault(status.getDesiredNumberScheduled(), 0);
        final int updated = orDefault(status.getUpdatedNumberScheduled(), 0);
        final int available = orDefault(status.getNumberAvailable(), 0);
        if (updated < desired || available < desired) {
            return Readiness.inProgress(Math.min(updated, available) + "/" + desired + " pods available");
        }
        return Readiness.ready();
    }

    static Readiness replicaSet(final ReplicaSet replicaSet) {
        final ReplicaSetStatus status = replicaSet.getStatus();
        final Readiness unobserved = unobserved(replicaSet, status == null ? null : status.getObservedGeneration(),
                status != null);
        if (unobserved != null) {
            return unobserved;
        }
        final int replicas = orDefault(replicaSet.getSpec().getReplicas(), DEFAULT_REPLICAS);
        final int available = orDefault(status.getAvailableReplicas(), 0);
        if (available < replicas) {
            return Readiness.inProgress(available + "/" + replicas + " replicas available");
        }
        return Readiness.ready();
    }

    static Readiness job(final Job job) {
        if (job.getStatus() != null && job.getStatus().getConditions() != null) {
            for (final JobCondition condition : job.getStatus().getConditions()) {
                if (Conditions.TRUE.equals(condition.getStatus())) {
                    if (COMPLETE.equals(condition.getType())) {
                        return Readiness.ready();
                    }
                    if (FAILED.equals(condition.getType())) {
                        return Readiness.failed(condition.getMessage());
                    }
                }
            }
        }
        return Readiness.inProgress("running");
    }

    static Readiness pod(final Pod pod) {
        if (pod.getStatus() == null) {
            return Readiness.inProgress(NO_STATUS);
        }
        final String phase = pod.getStatus().getPhase();
        if (SUCCEEDED.equals(phase)) {
            return Readiness.ready();
        }
        if (FAILED.equals(phase)) {
            return Readiness.failed(pod.getStatus().getMessage());
        }
        if (pod.getStatus().getConditions() != null) {
            for (final PodCondition condition : pod.getStatus().getConditions()) {
                if (Conditions.READY.equals(condition.getType()) && Conditions.TRUE.equals(condition.getStatus())) {
                    return Readiness.ready();
                }
            }
        }
        return Readiness.inProgress(phase != null ? phase : PENDING);
    }

    static Readiness persistentVolumeClaim(final PersistentVolumeClaim claim) {
        final String phase = claim.getStatus() != null ? claim.getStatus().getPhase() : null;
        if (BOUND.equals(phase)) {
            return Readiness.ready();
        }
        if (LOST.equals(phase)) {
            return Readiness.failed("the volume is lost");
        }
        return Readiness.inProgress(phase != null ? phase : PENDING);
    }

    static Readiness service(final Service service) {
        if (service.getSpec() == null || !LOAD_BALANCER.equals(service.getSpec().getType())) {
            return Readiness.ready();
        }
        final boolean hasIngress = service.getStatus() != null
                && service.getStatus().getLoadBalancer() != null
                && service.getStatus().getLoadBalancer().getIngress() != null
                && !service.getStatus().getLoadBalancer().getIngress().isEmpty();
        return hasIngress ? Readiness.ready() : Readiness.inProgress("waiting for a load balancer");
    }

    // in progress until the controller of the workload reports on its latest spec; null once it does
    private static Readiness unobserved(final HasMetadata workload,
                                        final Long observedGeneration,
                                        final boolean hasStatus) {
        if (!hasStatus) {
            return Readiness.inProgress(NO_STATUS);
        }
        if (behind(workload.getMetadata().getGeneration(), observedGeneration)) {
            return Readiness.inProgress(GENERATION_NOT_OBSERVED);
        }
        return null;
    }

    private static boolean behind(final Long generation,
                                  final Long observedGeneration) {
        return generation != null && (observedGeneration == null || observedGeneration < generation);
    }

    private static int orDefault(final Integer value,
                                 final int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
