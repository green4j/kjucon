/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.green4j.kjucon.example.greeting.GreetingOperator;
import io.github.green4j.kjucon.example.webapp.WebAppOperator;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

/**
 * One k3s for all the integration tests of a run, with the CRDs of the example installed. Each test class works in
 * a namespace of its own.
 */
public final class K3sCluster {
    /** How long the tests wait for what a real API server and its controllers do. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static K3sContainer k3s;
    private static KubernetesClient client;

    private K3sCluster() {
    }

    /**
     * @return a client of the cluster, started on the first call
     */
    public static synchronized KubernetesClient client() {
        if (client == null) {
            k3s = new K3sContainer(DockerImageName.parse(System.getProperty("kjucon.k3s.image"))
                    .asCompatibleSubstituteFor("rancher/k3s"));
            k3s.start();
            client = new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml())).build();
            for (final String crds : List.of(WebAppOperator.CRDS, GreetingOperator.CRDS)) {
                Examples.installCrds(client, crds);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                client.close();
                k3s.stop();
            }));
        }
        return client;
    }

    /**
     * @param name of a namespace, created if there is none
     * @return a client of the cluster
     */
    public static KubernetesClient namespace(final String name) {
        final KubernetesClient c = client();
        c.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build())
                .serverSideApply();
        return c;
    }
}
