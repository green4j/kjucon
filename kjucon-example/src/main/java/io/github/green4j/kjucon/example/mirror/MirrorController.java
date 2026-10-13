/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.mirror;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.example.Examples;

/**
 * The example of a controller with no custom resource: {@link ConfigMapMirror} copies the ConfigMaps labelled
 * {@code mirror=true} of the current kubeconfig context's namespace into a namespace of a target cluster - the
 * same one, or the cluster of another kubeconfig context:
 * <pre>
 * ./gradlew :kjucon-example:runMirror --args='--to mirrored'
 * ./gradlew :kjucon-example:runMirror --args='--to mirrored --target-context other-cluster'
 * kubectl apply -f kjucon-example/src/main/resources/samples/mirror/
 * </pre>
 * No CRD to install; the target namespace must exist.
 */
public final class MirrorController {
    private static final String TO = "--to";
    private static final String TARGET_CONTEXT = "--target-context";
    // the exit status of a command used wrong
    private static final int USAGE = 2;

    private MirrorController() {
    }

    /**
     * Configures the controller without starting it.
     *
     * @param source client of the cluster the originals are in
     * @param namespace of the originals
     * @param target client of the cluster the copies go to - the source one too
     * @param targetNamespace of the copies
     * @return the operator running the controller
     */
    public static Operator create(final KubernetesClient source,
                                  final String namespace,
                                  final KubernetesClient target,
                                  final String targetNamespace) {
        final Operator.Builder builder = Examples.operator(source, namespace);
        builder.controller(ConfigMap.class, new ConfigMapMirror(target, targetNamespace));
        return builder.build();
    }

    /**
     * @param args {@code --to <namespace>} of the copies, and optionally {@code --target-context <context>} of the
     *             kubeconfig for a target cluster other than the current one
     * @throws InterruptedException if interrupted while running
     */
    public static void main(final String[] args) throws InterruptedException {
        final String to = Examples.option(args, TO);
        if (to == null) {
            System.err.println("Usage: " + TO + " <namespace> [" + TARGET_CONTEXT + " <kubeconfig context>]");
            System.exit(USAGE);
        }
        final String targetContext = Examples.option(args, TARGET_CONTEXT);
        try (KubernetesClient source = new KubernetesClientBuilder().build();
                KubernetesClient target = targetContext == null ? null
                        : new KubernetesClientBuilder().withConfig(Config.autoConfigure(targetContext)).build()) {
            final String namespace = Examples.namespace(source);
            Examples.run(create(source, namespace, target != null ? target : source, to),
                    "Mirroring the ConfigMaps of namespace " + namespace + " to "
                            + (targetContext == null ? "" : targetContext + '/') + to);
        }
    }
}
