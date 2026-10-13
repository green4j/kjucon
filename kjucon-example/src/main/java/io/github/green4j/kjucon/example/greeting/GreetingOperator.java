/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.greeting;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.example.Examples;

/**
 * The plainest example: one custom resource, {@link Greeting}, and one plain reconciler, on kjucon-core alone.
 * Run it against the cluster of the current kubeconfig context:
 * <pre>
 * ./gradlew :kjucon-example:runGreetings --args='--install-crds'
 * kubectl apply -f kjucon-example/src/main/resources/samples/greeting/
 * kubectl get greetings
 * </pre>
 */
public final class GreetingOperator {
    /** The CRD of the example, on the classpath. */
    public static final String CRDS = "crds/greeting-crds.yaml";

    private GreetingOperator() {
    }

    /**
     * Configures the operator without starting it.
     *
     * @param client to use
     * @param namespace to watch
     * @return the operator
     */
    public static Operator create(final KubernetesClient client,
                                  final String namespace) {
        final Operator.Builder builder = Examples.operator(client, namespace);
        builder.controller(Greeting.class, new GreetingReconciler());
        return builder.build();
    }

    /**
     * @param args {@code --install-crds} to install the CRD first
     * @throws InterruptedException if interrupted while running
     */
    public static void main(final String[] args) throws InterruptedException {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Examples.installCrds(args, client, CRDS);
            final String namespace = Examples.namespace(client);
            Examples.run(create(client, namespace), "Keeping the Greetings of namespace " + namespace);
        }
    }
}
