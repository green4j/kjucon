/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.status.Conditions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Installs CustomResourceDefinitions - for an operator which ships its own, and for the tests which need them in
 * a fresh cluster.
 */
public final class Crds {
    private static final String ESTABLISHED = "Established";


    private Crds() {
    }

    /**
     * Applies the definitions found in a classpath resource with server-side apply, and waits until each of them
     * is Established.
     *
     * @param client to apply with
     * @param fieldManager of the apply
     * @param classpathResource a YAML or JSON document, one or more definitions
     * @param timeout to wait for each definition
     * @return the applied definitions
     */
    public static List<CustomResourceDefinition> apply(final KubernetesClient client,
                                                       final String fieldManager,
                                                       final String classpathResource,
                                                       final Duration timeout) {
        final ClassLoader loader = Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader() : Crds.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Not found on the classpath: " + classpathResource);
            }
            final List<CustomResourceDefinition> crds = new ArrayList<>();
            for (final HasMetadata item : client.load(in).items()) {
                if (!(item instanceof CustomResourceDefinition)) {
                    throw new IllegalArgumentException(classpathResource + " holds a " + item.getKind()
                            + ", not only CustomResourceDefinitions");
                }
                crds.add((CustomResourceDefinition) item);
            }
            for (final CustomResourceDefinition crd : crds) {
                client.resource(crd).fieldManager(fieldManager).forceConflicts().serverSideApply();
            }
            for (final CustomResourceDefinition crd : crds) {
                awaitEstablished(client, crd.getMetadata().getName(), timeout);
            }
            return crds;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Waits until a definition is Established, so that resources of its kind can be created.
     *
     * @param client to read with
     * @param name of the definition, plural.group
     * @param timeout to wait
     */
    public static void awaitEstablished(final KubernetesClient client,
                                        final String name,
                                        final Duration timeout) {
        client.apiextensions().v1().customResourceDefinitions()
                .withName(name)
                .waitUntilCondition(Crds::isEstablished, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean isEstablished(final CustomResourceDefinition crd) {
        if (crd == null || crd.getStatus() == null || crd.getStatus().getConditions() == null) {
            return false;
        }
        for (final CustomResourceDefinitionCondition condition : crd.getStatus().getConditions()) {
            if (ESTABLISHED.equals(condition.getType()) && Conditions.TRUE.equals(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }
}
