/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.example.greeting.Greeting;
import io.github.green4j.kjucon.example.greeting.GreetingOperator;
import io.github.green4j.kjucon.example.greeting.GreetingReconciler;
import io.github.green4j.kjucon.example.greeting.GreetingSpec;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import io.github.green4j.kjucon.test.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.green4j.kjucon.example.K3sCluster.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plainest example: a greeting kept by a plain reconciler.
 */
class GreetingOperatorIntTest {
    private static final String NS = "greetings";

    private static KubernetesClient client;
    private static Operator operator;

    @BeforeAll
    static void start() {
        client = K3sCluster.namespace(NS);
        operator = GreetingOperator.create(client, NS);
        Await.started(operator, TIMEOUT);
    }

    @AfterAll
    static void stop() {
        if (operator != null) {
            operator.close();
        }
    }

    @Test
    void greetingIsKeptAndReportsItsStatus() {
        final String world = "world";
        client.resource(greeting(world, "World")).create();
        Await.until(() -> "Hello, World!".equals(text(world)), TIMEOUT, "the greeting's ConfigMap");
        Await.until(() -> hasPhase(world, StandardStatus.READY), TIMEOUT, "Ready");

        edit(world, "kjucon");
        Await.until(() -> "Hello, kjucon!".equals(text(world)), TIMEOUT, "the new text");

        edit(world, " ");
        Await.until(() -> hasPhase(world, StandardStatus.ERROR), TIMEOUT, "Error for a blank spec.to");
        assertEquals("spec.to is required", greetingNamed(world).getStatus().getMessage());

        client.resources(Greeting.class).inNamespace(NS).withName(world).delete();
        Await.until(() -> configMap(NS, world + "-greeting") == null, TIMEOUT,
                "the ConfigMap to be garbage collected with its owner");
    }

    private static Greeting greeting(final String name,
                                     final String to) {
        final Greeting greeting = new Greeting();
        greeting.getMetadata().setNamespace(NS);
        greeting.getMetadata().setName(name);
        final GreetingSpec spec = new GreetingSpec();
        spec.setTo(to);
        greeting.setSpec(spec);
        return greeting;
    }

    private static void edit(final String name,
                             final String to) {
        Conflicts.retry(() -> client.resources(Greeting.class).inNamespace(NS).withName(name).edit(g -> {
            g.getSpec().setTo(to);
            return g;
        }));
    }

    private static Greeting greetingNamed(final String name) {
        return client.resources(Greeting.class).inNamespace(NS).withName(name).get();
    }

    private static boolean hasPhase(final String name,
                                    final String phase) {
        return Fixtures.hasPhase(greetingNamed(name), phase);
    }

    private static String text(final String greeting) {
        final ConfigMap configMap = configMap(NS, greeting + "-greeting");
        return configMap == null ? null : configMap.getData().get(GreetingReconciler.TEXT);
    }

    private static ConfigMap configMap(final String namespace,
                                       final String name) {
        return client.configMaps().inNamespace(namespace).withName(name).get();
    }
}
