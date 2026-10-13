/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.example.mirror.ConfigMapMirror;
import io.github.green4j.kjucon.example.mirror.MirrorController;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.green4j.kjucon.example.K3sCluster.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A controller with no custom resource: ConfigMaps mirrored to another namespace - of the same cluster here.
 */
class MirrorControllerIntTest {
    private static final String NS = "mirror-source";
    private static final String MIRROR_NS = "mirror-target";

    private static KubernetesClient client;
    private static Operator operator;

    @BeforeAll
    static void start() {
        client = K3sCluster.namespace(NS);
        K3sCluster.namespace(MIRROR_NS);
        operator = MirrorController.create(client, NS, client, MIRROR_NS);
        Await.started(operator, TIMEOUT);
    }

    @AfterAll
    static void stop() {
        if (operator != null) {
            operator.close();
        }
    }

    @Test
    void labelledConfigMapsOnlyAreMirroredUpdatedAndDeletedWithTheOriginal() {
        final String size = "size";
        final String color = "color";
        final String settings = "settings";
        client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(NS).withName("local").endMetadata()
                .addToData("a", "1").build()).create(); // not labelled
        client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(NS).withName(settings)
                .addToLabels(ConfigMapMirror.LABEL, ConfigMapMirror.ENABLED).endMetadata()
                .addToData(color, "blue").addToData(size, "L").build()).create();
        final String copy = NS + "-settings";
        Await.until(() -> configMap(MIRROR_NS, copy) != null, TIMEOUT, "the copy");
        assertNull(configMap(MIRROR_NS, NS + "-local"), "not labelled: not mirrored");
        assertFalse(client.configMaps().inNamespace(NS).withName("local").get().getMetadata().getFinalizers()
                .contains(Examples.DOMAIN + "/" + ConfigMapMirror.NAME), "nor held by the mirror");
        assertEquals(Map.of(color, "blue", size, "L"), configMap(MIRROR_NS, copy).getData());
        assertEquals(NS + "/settings", configMap(MIRROR_NS, copy).getMetadata().getAnnotations()
                .get(ConfigMapMirror.SOURCE));

        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(settings).edit(c -> {
            c.getData().put(color, "green");
            c.getData().remove(size);
            return c;
        }));
        Await.until(() -> Map.of(color, "green").equals(configMap(MIRROR_NS, copy).getData()), TIMEOUT,
                "the copy to follow, a removed key included");

        client.configMaps().inNamespace(NS).withName(settings).delete();
        Await.until(() -> configMap(NS, settings) == null, TIMEOUT, "the original to go once its copy is gone");
        assertNull(configMap(MIRROR_NS, copy), "the copy deleted by the cleanup");
    }

    private static ConfigMap configMap(final String namespace,
                                       final String name) {
        return client.configMaps().inNamespace(namespace).withName(name).get();
    }
}
