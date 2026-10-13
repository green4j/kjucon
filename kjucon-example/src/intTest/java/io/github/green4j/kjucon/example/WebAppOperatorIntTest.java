/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.Labels;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.ReconcileRequest;
import io.github.green4j.kjucon.compose.CompositionObserver;
import io.github.green4j.kjucon.example.webapp.WebApp;
import io.github.green4j.kjucon.example.webapp.WebAppOperator;
import io.github.green4j.kjucon.example.webapp.WebAppSet;
import io.github.green4j.kjucon.example.webapp.WebAppSetSpec;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.github.green4j.kjucon.example.K3sCluster.TIMEOUT;
import static io.github.green4j.kjucon.test.Fixtures.hasPhase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The example operator against a real API server: server-side apply, owner references, finalizers and status
 * subresources as Kubernetes implements them, two levels of composition deep.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebAppOperatorIntTest {
    private static final String NS = "default";
    // tiny, starts at once, and needs no port to be ready: what a test of the orchestration wants
    private static final String IMAGE = "registry.k8s.io/pause:3.10";
    private static final String SET = "shop";
    private static final String FRONT_APP = "front";
    private static final String ADMIN_APP = "admin";
    // the WebApps of the set's applications
    private static final String FRONT = SET + '-' + FRONT_APP;
    private static final String ADMIN = SET + '-' + ADMIN_APP;
    // pods are pulled and started: longer than the API server alone takes
    private static final Duration ROLLOUT = Duration.ofMinutes(3);

    private static KubernetesClient client;
    private static Operator operator;
    private static final List<String> DELETED = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        client = K3sCluster.namespace(NS);

        operator = WebAppOperator.create(client, NS, new CompositionObserver() {
            @Override
            public void onDeleted(final ReconcileRequest primary,
                                  final HasMetadata object) {
                DELETED.add(object.getKind() + '/' + object.getMetadata().getName());
            }
        });
        Await.started(operator, TIMEOUT);
    }

    @AfterAll
    static void stop() {
        if (operator != null) {
            operator.close();
        }
    }

    @Test
    @Order(1)
    void composesTwoLevelsAndBecomesReady() {
        client.resource(set(SET, app(FRONT_APP, 2), app(ADMIN_APP, 1))).create();

        Await.until(() -> hasPhase(webAppSet(SET), StandardStatus.READY), ROLLOUT, "the WebAppSet to be Ready");

        for (final String app : List.of(FRONT, ADMIN)) {
            final WebApp webApp = webApp(app);
            assertTrue(webApp.getStatus().hasPhase(StandardStatus.READY), app + ": " + webApp.getStatus()
                    + " generation " + webApp.getMetadata().getGeneration());
            assertEquals("blue", webApp.getMetadata().getLabels().get("team"), "propagated");
            assertEquals(SET, webApp.getMetadata().getOwnerReferences().get(0).getName());
            assertNotNull(client.services().inNamespace(NS).withName(app).get());
            assertNotNull(configMap(app + "-page"));
        }
        assertEquals(2, deployment(FRONT).getStatus().getAvailableReplicas());
        assertEquals("webapps", deployment(FRONT).getMetadata().getLabels().get(Labels.MANAGED_BY));
    }

    @Test
    @Order(2)
    void keepsFieldsOtherManagersOwn() {
        Conflicts.retry(() -> client.apps().deployments().inNamespace(NS).withName(FRONT).edit(d -> {
            d.getMetadata().getLabels().put("owned-by-someone-else", "yes");
            return d;
        }));
        editSet(SET, s -> s.getSpec().getApps().get(0).setMessage("Changed"));

        Await.until(() -> configMap(FRONT + "-page").getData().get("index.html").contains("Changed"), ROLLOUT,
                "the page to change");
        assertEquals("yes", deployment(FRONT).getMetadata().getLabels().get("owned-by-someone-else"),
                "server-side apply keeps what another manager set");
        Await.until(() -> hasPhase(webAppSet(SET), StandardStatus.READY), ROLLOUT, "Ready again after the rollout");
    }

    @Test
    @Order(3)
    void prunesARemovedAppWithEverythingItOwns() {
        editSet(SET, s -> s.getSpec().getApps().removeIf(a -> ADMIN_APP.equals(a.getName())));

        Await.until(() -> webApp(ADMIN) == null, ROLLOUT, "the WebApp to be pruned");
        Await.until(() -> deployment(ADMIN) == null && configMap(ADMIN + "-page") == null, ROLLOUT,
                "what it owned to be gone");
        assertTrue(DELETED.indexOf("Service/shop-admin") < DELETED.indexOf("Deployment/shop-admin"),
                DELETED.toString());
        assertTrue(DELETED.indexOf("Deployment/shop-admin") < DELETED.indexOf("ConfigMap/shop-admin-page"),
                DELETED.toString());
        assertNotNull(webApp(FRONT));
    }

    @Test
    @Order(4)
    void deletesTheWholeHierarchy() {
        client.resources(WebAppSet.class).inNamespace(NS).withName(SET).delete();

        Await.until(() -> webAppSet(SET) == null, ROLLOUT, "the WebAppSet to be gone");
        assertNull(webApp(FRONT));
        Await.until(() -> deployment(FRONT) == null, ROLLOUT, "the deployment to be gone");
        assertTrue(operator.health().isLive());
    }

    private static WebAppSet set(final String name,
                                 final WebAppSetSpec.App... apps) {
        final WebAppSet set = new WebAppSet();
        set.getMetadata().setName(name);
        set.getMetadata().setNamespace(NS);
        set.getMetadata().setLabels(Map.of("team", "blue"));
        final WebAppSetSpec spec = new WebAppSetSpec();
        spec.setApps(new java.util.ArrayList<>(List.of(apps)));
        set.setSpec(spec);
        return set;
    }

    private static WebAppSetSpec.App app(final String name,
                                         final int replicas) {
        final WebAppSetSpec.App app = new WebAppSetSpec.App();
        app.setName(name);
        app.setReplicas(replicas);
        app.setImage(IMAGE);
        app.setMessage("Hello " + name);
        return app;
    }

    private static void editSet(final String name,
                                final Consumer<WebAppSet> change) {
        Conflicts.retry(() -> client.resources(WebAppSet.class).inNamespace(NS).withName(name).edit(s -> {
            change.accept(s);
            return s;
        }));
    }


    private static WebAppSet webAppSet(final String name) {
        return client.resources(WebAppSet.class).inNamespace(NS).withName(name).get();
    }

    private static WebApp webApp(final String name) {
        return client.resources(WebApp.class).inNamespace(NS).withName(name).get();
    }

    private static Deployment deployment(final String name) {
        return client.apps().deployments().inNamespace(NS).withName(name).get();
    }

    private static ConfigMap configMap(final String name) {
        return client.configMaps().inNamespace(NS).withName(name).get();
    }
}
