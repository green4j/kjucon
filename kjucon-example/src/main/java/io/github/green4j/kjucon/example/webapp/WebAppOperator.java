/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.compose.CompositionObserver;
import io.github.green4j.kjucon.compose.CompositionReconciler;
import io.github.green4j.kjucon.example.Examples;
import io.github.green4j.kjucon.example.StdOutCompositionObserver;

/**
 * The composition example: two levels of it.
 * <pre>
 * WebAppSet --composes--&gt; WebApp (one per app) --composes--&gt; ConfigMap, Deployment, Service
 * </pre>
 * Run it against the cluster of the current kubeconfig context:
 * <pre>
 * ./gradlew :kjucon-example:runWebApps --args='--install-crds'
 * kubectl apply -f kjucon-example/src/main/resources/samples/webapp/
 * </pre>
 */
public final class WebAppOperator {
    /** The CRDs of the example, on the classpath. */
    public static final String CRDS = "crds/webapp-crds.yaml";

    private WebAppOperator() {
    }

    /**
     * Configures the operator - its two composition controllers - without starting it.
     *
     * @param client to use
     * @param namespace to watch
     * @param compositionObserver of both compositions, may be null
     * @return the operator
     */
    public static Operator create(final KubernetesClient client,
                                  final String namespace,
                                  final CompositionObserver compositionObserver) {
        final Operator.Builder builder = Examples.operator(client, namespace);

        builder.controller(WebAppSet.class,
                        CompositionReconciler.builder(new WebAppSetComposition())
                                .manages(WebApp.class)
                                .observer(compositionObserver)
                                .build())
                .name("webappsets");

        builder.controller(WebApp.class,
                        CompositionReconciler.builder(new WebAppComposition())
                                .manages(ConfigMap.class, Deployment.class, Service.class)
                                .observer(compositionObserver)
                                .build())
                .name("webapps")
                .workers(2);

        return builder.build();
    }

    /**
     * @param args {@code --install-crds} to install the CRDs first
     * @throws InterruptedException if interrupted while running
     */
    public static void main(final String[] args) throws InterruptedException {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Examples.installCrds(args, client, CRDS);
            final String namespace = Examples.namespace(client);
            Examples.run(create(client, namespace, new StdOutCompositionObserver()),
                    "Composing WebAppSets and WebApps in namespace " + namespace);
        }
    }
}
