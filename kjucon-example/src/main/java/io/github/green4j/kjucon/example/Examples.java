/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.util.Crds;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * What the examples share: the API group and label domain, and running an operator until the process is stopped.
 * Each example has a main class of its own, from the plainest up:
 * <ul>
 *     <li>{@link io.github.green4j.kjucon.example.greeting.GreetingOperator} - one custom resource, one plain
 *     reconciler;</li>
 *     <li>{@link io.github.green4j.kjucon.example.mirror.MirrorController} - a controller of ConfigMaps with no
 *     custom resource, writing to a target cluster;</li>
 *     <li>{@link io.github.green4j.kjucon.example.webapp.WebAppOperator} - two levels of composition.</li>
 * </ul>
 */
public final class Examples {
    /** The API group of the example resources. */
    public static final String GROUP = "example.kjucon.green4j.github.io";
    /** The label domain of the examples. */
    public static final String DOMAIN = GROUP;
    /** The field manager installing the CRDs. */
    public static final String CRD_MANAGER = "kjucon-example";
    /** The port of the health endpoints. */
    public static final int HEALTH_PORT = 8081;
    /** The option of a main method which installs the CRDs of its example first. */
    public static final String INSTALL_CRDS = "--install-crds";

    private static final String DEFAULT_NAMESPACE = "default";
    private static final Duration CRD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration API_SERVER_PROBE = Duration.ofSeconds(10);

    private Examples() {
    }

    /**
     * @param client of the cluster
     * @param namespace to watch
     * @return a builder of an operator as every example has it: the label domain of the examples, an API server
     *         probe and an observer printing what the controllers do
     */
    public static Operator.Builder operator(final KubernetesClient client,
                                            final String namespace) {
        return Operator.builder()
                .client(client)
                .watchNamespace(namespace)
                .labelDomain(DOMAIN)
                .apiServerProbe(API_SERVER_PROBE)
                .observer(new StdOutControllerObserver());
    }

    /**
     * Installs CRDs if the arguments of a main method ask for it with {@link #INSTALL_CRDS}.
     *
     * @param args of the main method
     * @param client of the cluster
     * @param crds the CRDs, on the classpath
     */
    public static void installCrds(final String[] args,
                                   final KubernetesClient client,
                                   final String crds) {
        if (List.of(args).contains(INSTALL_CRDS)) {
            installCrds(client, crds);
        }
    }

    /**
     * Installs CRDs and waits until they are established.
     *
     * @param client of the cluster
     * @param crds the CRDs, on the classpath
     */
    public static void installCrds(final KubernetesClient client,
                                   final String crds) {
        Crds.apply(client, CRD_MANAGER, crds, CRD_TIMEOUT);
    }

    /**
     * @param client of the cluster
     * @return the namespace of the client's kubeconfig context, or "default"
     */
    public static String namespace(final KubernetesClient client) {
        return client.getNamespace() != null ? client.getNamespace() : DEFAULT_NAMESPACE;
    }

    /**
     * @param args of a main method
     * @param option such as {@code --to}
     * @return the value after the option, null if there is none
     */
    public static String option(final String[] args,
                                final String option) {
        final int index = List.of(args).indexOf(option);
        return index >= 0 && index + 1 < args.length ? args[index + 1] : null;
    }

    /**
     * Starts the operator, serves its health and waits until the process is stopped, Ctrl+C or a signal.
     *
     * @param operator to run, closed when it stops
     * @param what the operator does, printed when it is up
     * @throws InterruptedException if interrupted while running
     */
    public static void run(final Operator operator,
                           final String what) throws InterruptedException {
        final CountDownLatch stopped = new CountDownLatch(1);
        try (operator; HealthServer health = new HealthServer(operator, HEALTH_PORT)) {
            Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown));
            operator.start();
            System.out.printf("%s; health on :%d/healthz and /readyz; Ctrl+C to stop%n", what, health.port());
            stopped.await();
        }
    }
}
