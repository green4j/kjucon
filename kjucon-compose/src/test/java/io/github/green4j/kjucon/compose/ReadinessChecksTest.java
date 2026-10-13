/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.github.green4j.kjucon.status.Conditions;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.green4j.kjucon.compose.Readiness.State.FAILED;
import static io.github.green4j.kjucon.compose.Readiness.State.IN_PROGRESS;
import static io.github.green4j.kjucon.compose.Readiness.State.NOT_FOUND;
import static io.github.green4j.kjucon.compose.Readiness.State.READY;
import static io.github.green4j.kjucon.compose.Readiness.State.TERMINATING;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadinessChecksTest {
    private static final String STATUS = "status";
    private static final String CONDITIONS = "conditions";
    private static final String TYPE = "type";
    private static final String OBSERVED_GENERATION = "observedGeneration";
    private static final long GENERATION = 2L;

    static Stream<Arguments> objects() {
        return Stream.of(
                Arguments.of("nothing", null, NOT_FOUND),
                Arguments.of("an object being deleted", new ConfigMapBuilder().withNewMetadata().withName("c")
                        .withDeletionTimestamp("2026-01-01T00:00:00Z").endMetadata().build(), TERMINATING),
                Arguments.of("an object without a status", new ConfigMapBuilder().withNewMetadata().withName("c")
                        .endMetadata().build(), READY),
                Arguments.of("an Ingress, by default", ingress(), READY),

                Arguments.of("a Deployment of an older generation", deployment(1L, 3, 3, 3, 3), IN_PROGRESS),
                Arguments.of("a Deployment not all updated", deployment(GENERATION, 2, 3, 3, 3), IN_PROGRESS),
                Arguments.of("a Deployment with old replicas left", deployment(GENERATION, 3, 4, 3, 3), IN_PROGRESS),
                Arguments.of("a Deployment not all available", deployment(GENERATION, 3, 3, 2, 2), IN_PROGRESS),
                Arguments.of("a Deployment rolled out", deployment(GENERATION, 3, 3, 3, 3), READY),
                Arguments.of("a Deployment past its progress deadline", stuck(), FAILED),

                Arguments.of("a StatefulSet without a status", new StatefulSetBuilder().withNewMetadata()
                        .withName("s").endMetadata().withNewSpec().withReplicas(3).endSpec().build(), IN_PROGRESS),
                Arguments.of("a StatefulSet not all ready", statefulSet(s -> s.getStatus().setReadyReplicas(2), 0,
                        "b", "b"), IN_PROGRESS),
                Arguments.of("a StatefulSet rolling out", statefulSet(s -> { }, 0, "a", "b"), IN_PROGRESS),
                Arguments.of("a StatefulSet rolled out", statefulSet(s -> { }, 0, "b", "b"), READY),
                Arguments.of("a StatefulSet updated on delete", statefulSet(s -> s.getSpec().getUpdateStrategy()
                        .setType("OnDelete"), 0, "a", "b"), READY),
                Arguments.of("a partitioned StatefulSet before its part", partitioned(0), IN_PROGRESS),
                Arguments.of("a partitioned StatefulSet once its part", partitioned(1), READY),

                Arguments.of("a DaemonSet of an older generation", daemonSet(1L, 2, 2, 2), IN_PROGRESS),
                Arguments.of("a DaemonSet not on every node yet", daemonSet(GENERATION, 2, 1, 1), IN_PROGRESS),
                Arguments.of("a DaemonSet on every node", daemonSet(GENERATION, 2, 2, 2), READY),
                Arguments.of("a ReplicaSet not all available", replicaSet(1), IN_PROGRESS),
                Arguments.of("a ReplicaSet all available", replicaSet(3), READY),

                Arguments.of("a running Job", new JobBuilder().withNewMetadata().withName("j").endMetadata()
                        .build(), IN_PROGRESS),
                Arguments.of("a complete Job", job("Complete"), READY),
                Arguments.of("a failed Job", job("Failed"), FAILED),
                Arguments.of("a ready Pod", new PodBuilder().withNewMetadata().withName("p").endMetadata()
                        .withNewStatus().withPhase("Running").addNewCondition().withType(Conditions.READY)
                        .withStatus(Conditions.TRUE).endCondition().endStatus().build(), READY),
                Arguments.of("a Pod without a status", new PodBuilder().withNewMetadata().withName("p").endMetadata()
                        .build(), IN_PROGRESS),
                Arguments.of("a pending Pod", pod("Pending"), IN_PROGRESS),
                Arguments.of("a succeeded Pod", pod("Succeeded"), READY),
                Arguments.of("a failed Pod", pod("Failed"), FAILED),
                Arguments.of("a pending claim", claim("Pending"), IN_PROGRESS),
                Arguments.of("a bound claim", claim("Bound"), READY),
                Arguments.of("a lost claim", claim("Lost"), FAILED),
                Arguments.of("a LoadBalancer without an address", service("LoadBalancer"), IN_PROGRESS),
                Arguments.of("a LoadBalancer with an address", new ServiceBuilder(service("LoadBalancer"))
                        .withNewStatus().withNewLoadBalancer().addNewIngress().withIp("10.0.0.1").endIngress()
                        .endLoadBalancer().endStatus().build(), READY),
                Arguments.of("a ClusterIP Service", service("ClusterIP"), READY),

                Arguments.of("a child not reconciled yet", widget(null), IN_PROGRESS),
                Arguments.of("a child ready for an older generation",
                        widget(s -> s.markReady(GENERATION - 1)), IN_PROGRESS),
                Arguments.of("a pending child", widget(s -> s.markPending(GENERATION)), IN_PROGRESS),
                Arguments.of("a ready child", widget(s -> s.markReady(GENERATION)), READY),
                Arguments.of("a failed child", widget(s -> s.markError("broken", GENERATION)), FAILED),

                Arguments.of("Ready True", generic(Map.of(CONDITIONS, List.of(
                        Map.of(TYPE, Conditions.READY, STATUS, Conditions.TRUE)))), READY),
                Arguments.of("Ready False", generic(Map.of(CONDITIONS, List.of(
                        Map.of(TYPE, Conditions.READY, STATUS, Conditions.FALSE)))), IN_PROGRESS),
                Arguments.of("Stalled True", generic(Map.of(CONDITIONS, List.of(
                        Map.of(TYPE, Conditions.STALLED, STATUS, Conditions.TRUE)))), FAILED),
                Arguments.of("Reconciling True without Ready", generic(Map.of(CONDITIONS, List.of(
                        Map.of(TYPE, Conditions.RECONCILING, STATUS, Conditions.TRUE)))), IN_PROGRESS),
                Arguments.of("other conditions only", generic(Map.of(CONDITIONS, List.of(
                        Map.of(TYPE, "Available", STATUS, Conditions.TRUE)))), READY),
                Arguments.of("an older generation observed", generic(Map.of(OBSERVED_GENERATION, 1)), IN_PROGRESS),
                Arguments.of("the generation observed", generic(Map.of(OBSERVED_GENERATION, 2)), READY));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("objects")
    void builtInChecks(final String what,
                       final HasMetadata object,
                       final Readiness.State expected) {
        assertEquals(expected, ReadinessChecks.defaults().check(object).state());
    }

    static Stream<Arguments> otherChecks() {
        return Stream.of(
                Arguments.of("an Ingress waiting for an address", ReadinessChecks.defaults()
                        .with(Ingress.class, ReadinessChecks.INGRESS_WITH_ADDRESS), ingress(), IN_PROGRESS),
                Arguments.of("a custom check replacing the built-in one", ReadinessChecks.defaults()
                        .with(Deployment.class, d -> Readiness.ready()), deployment(1L, 0, 0, 0, 0), READY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherChecks")
    void checksOfAType(final String what,
                       final ReadinessChecks checks,
                       final HasMetadata object,
                       final Readiness.State expected) {
        assertEquals(expected, checks.check(object).state());
    }

    private static Ingress ingress() {
        return new IngressBuilder().withNewMetadata().withName("i").endMetadata().build();
    }

    // three replicas wanted, of the generation the controller observed
    private static Deployment deployment(final Long observed,
                                         final int updated,
                                         final int total,
                                         final int ready,
                                         final int available) {
        return new DeploymentBuilder()
                .withNewMetadata().withName("d").withGeneration(GENERATION).endMetadata()
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus()
                .withObservedGeneration(observed)
                .withUpdatedReplicas(updated)
                .withReplicas(total)
                .withReadyReplicas(ready)
                .withAvailableReplicas(available)
                .endStatus()
                .build();
    }

    private static Deployment stuck() {
        final Deployment stuck = deployment(GENERATION, 1, 3, 1, 1);
        stuck.getStatus().setConditions(List.of(new DeploymentConditionBuilder()
                .withType("Progressing").withStatus(Conditions.FALSE).withReason("ProgressDeadlineExceeded")
                .build()));
        return stuck;
    }

    private static StatefulSet statefulSet(final Consumer<StatefulSet> spec,
                                           final int updated,
                                           final String current,
                                           final String update) {
        final StatefulSet set = new StatefulSetBuilder()
                .withNewMetadata().withName("s").withGeneration(1L).endMetadata()
                .withNewSpec().withReplicas(3).withNewUpdateStrategy().withType("RollingUpdate").endUpdateStrategy()
                .endSpec()
                .withNewStatus().withObservedGeneration(1L).withReadyReplicas(3).withUpdatedReplicas(updated)
                .withCurrentRevision(current).withUpdateRevision(update).endStatus()
                .build();
        spec.accept(set);
        return set;
    }

    // only the pods from the second one up are updated
    private static StatefulSet partitioned(final int updated) {
        return statefulSet(s -> s.getSpec().getUpdateStrategy().setRollingUpdate(
                new io.fabric8.kubernetes.api.model.apps.RollingUpdateStatefulSetStrategyBuilder()
                        .withPartition(2).build()), updated, "a", "b");
    }

    private static DaemonSet daemonSet(final Long observed,
                                       final int desired,
                                       final int updated,
                                       final int available) {
        return new DaemonSetBuilder().withNewMetadata().withName("ds").withGeneration(GENERATION).endMetadata()
                .withNewStatus().withObservedGeneration(observed).withDesiredNumberScheduled(desired)
                .withUpdatedNumberScheduled(updated).withNumberAvailable(available).endStatus()
                .build();
    }

    // three replicas wanted
    private static ReplicaSet replicaSet(final int available) {
        return new ReplicaSetBuilder().withNewMetadata().withName("rs").withGeneration(GENERATION).endMetadata()
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus().withObservedGeneration(GENERATION).withAvailableReplicas(available).endStatus()
                .build();
    }

    private static Pod pod(final String phase) {
        return new PodBuilder().withNewMetadata().withName("p").endMetadata()
                .withNewStatus().withPhase(phase).endStatus().build();
    }

    private static PersistentVolumeClaim claim(final String phase) {
        return new PersistentVolumeClaimBuilder().withNewMetadata().withName("c").endMetadata()
                .withNewStatus().withPhase(phase).endStatus().build();
    }

    private static Job job(final String condition) {
        return new JobBuilder().withNewMetadata().withName("j").endMetadata()
                .withNewStatus().addNewCondition().withType(condition).withStatus(Conditions.TRUE).endCondition()
                .endStatus()
                .build();
    }

    private static io.fabric8.kubernetes.api.model.Service service(final String type) {
        return new ServiceBuilder().withNewMetadata().withName("s").endMetadata()
                .withNewSpec().withType(type).endSpec().build();
    }

    // a resource composed by another kjucon controller, with the status it reported, if any
    private static Widget widget(final Consumer<StandardStatus> status) {
        final Widget widget = Widget.of("ns", "w", 1);
        widget.getMetadata().setGeneration(GENERATION);
        if (status != null) {
            widget.setStatus(new StandardStatus());
            status.accept(widget.getStatus());
        }
        return widget;
    }

    private static GenericKubernetesResource generic(final Map<String, Object> status) {
        final GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("example.com/v1");
        resource.setKind("Thing");
        resource.setMetadata(new ObjectMetaBuilder().withName("t").withGeneration(GENERATION).build());
        resource.setAdditionalProperty(STATUS, status);
        return resource;
    }
}
