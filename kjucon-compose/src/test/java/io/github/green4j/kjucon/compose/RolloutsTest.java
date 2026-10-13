/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RolloutsTest {
    private static final String DOMAIN = "kjucon.example.com";
    private static final KubernetesSerialization SERIALIZATION = new KubernetesSerialization();

    static Stream<Arguments> workloads() {
        return Stream.of(
                Arguments.of(new DeploymentBuilder().withNewMetadata().withName("w").endMetadata()
                        .withNewSpec().withNewTemplate().endTemplate().endSpec().build(),
                        (Function<HasMetadata, PodTemplateSpec>) w -> ((Deployment) w).getSpec().getTemplate()),
                Arguments.of(new StatefulSetBuilder().withNewMetadata().withName("w").endMetadata()
                        .withNewSpec().withNewTemplate().endTemplate().endSpec().build(),
                        (Function<HasMetadata, PodTemplateSpec>) w -> ((StatefulSet) w).getSpec().getTemplate()),
                Arguments.of(new DaemonSetBuilder().withNewMetadata().withName("w").endMetadata()
                        .withNewSpec().withNewTemplate().endTemplate().endSpec().build(),
                        (Function<HasMetadata, PodTemplateSpec>) w -> ((DaemonSet) w).getSpec().getTemplate()));
    }

    @ParameterizedTest
    @MethodSource("workloads")
    void stampsThePodTemplateOfAWorkload(final HasMetadata workload,
                                         final Function<HasMetadata, PodTemplateSpec> template) {
        Rollouts.stamp(workload, List.of(secret("one", "x")), DOMAIN, SERIALIZATION);
        assertNotNull(template.apply(workload).getMetadata().getAnnotations().get(Rollouts.annotation(DOMAIN)));
    }

    @Test
    void refusesWhatHasNoPodTemplate() {
        assertThrows(IllegalArgumentException.class, () -> Rollouts.stamp(
                new ConfigMapBuilder().withNewMetadata().withName("c").endMetadata().build(),
                List.of(), DOMAIN, SERIALIZATION));
    }

    @Test
    void hashFollowsTheDataNotTheMetadata() {
        final String hash = hash(secret("one", "x"));
        assertNotEquals(hash, hash(secret("two", "x")), "the data changed");
        final Secret relabelled = secret("one", "x");
        relabelled.getMetadata().setLabels(Map.of("relabelled", "yes"));
        assertEquals(hash, hash(relabelled), "the metadata changed only");
    }

    private static String hash(final Secret source) {
        final Deployment workload = new DeploymentBuilder().withNewMetadata().withName("w").endMetadata()
                .withNewSpec().withNewTemplate().endTemplate().endSpec().build();
        Rollouts.stamp(workload, List.of(source), DOMAIN, SERIALIZATION);
        return workload.getSpec().getTemplate().getMetadata().getAnnotations().get(Rollouts.annotation(DOMAIN));
    }

    private static Secret secret(final String value,
                                 final String name) {
        return new SecretBuilder().withNewMetadata().withName(name).endMetadata()
                .addToStringData("key", value).build();
    }
}
