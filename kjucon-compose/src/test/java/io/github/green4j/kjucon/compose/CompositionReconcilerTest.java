/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.InvalidResourceException;
import io.github.green4j.kjucon.Labels;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.ReconcileRequest;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import io.github.green4j.kjucon.test.Fixtures;
import io.github.green4j.kjucon.test.MockKubernetes;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.green4j.kjucon.test.Fixtures.CONTROLLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionReconcilerTest {
    private static final String NS = Fixtures.NAMESPACE;
    private static final String DOMAIN = Fixtures.LABEL_DOMAIN;

    private MockKubernetes kubernetes;
    private KubernetesClient client;
    private Operator operator;

    private final AtomicBoolean configReady = new AtomicBoolean(true);
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final List<String> pruned = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() {
        kubernetes = new MockKubernetes();
        client = kubernetes.client();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(NS)
                .labelDomain(DOMAIN);
        builder.controller(Widget.class,
                        CompositionReconciler.builder(this::compose)
                                .manages(ConfigMap.class, Deployment.class, Service.class)
                                .cleanupPoll(Duration.ofMillis(100))
                                .observer(new CompositionObserver() {
                                    @Override
                                    public void onDeleted(final ReconcileRequest primary,
                                                          final HasMetadata object) {
                                        deleted.add(object.getKind() + '/' + object.getMetadata().getName());
                                    }

                                    @Override
                                    public void onPruned(final ReconcileRequest primary,
                                                         final HasMetadata object) {
                                        pruned.add(object.getKind() + '/' + object.getMetadata().getName());
                                    }
                                })
                                .build())
                .name(CONTROLLER);
        operator = builder.build();
        Await.started(operator);
    }

    @AfterEach
    void stop() {
        operator.close();
        kubernetes.close();
    }

    // <name>-config <- <name> (Deployment, rolled on the config) <- <name> (Service); size extra ConfigMaps; and
    // <name>-keep, orphaned when it leaves - until the text says "drop-keep"
    private Composition<Widget> compose(final ReconcileContext<Widget> context) {
        final Widget widget = context.primary();
        final String name = widget.getMetadata().getName();
        final int size = widget.getSpec().getSize();
        if (size < 0) {
            throw new InvalidResourceException("size must not be negative");
        }
        final Composition.Builder<Widget> builder = Composition.<Widget>builder()
                .propagateLabels("team");
        final Component<Widget, ConfigMap> config = builder.add(Component.<Widget, ConfigMap>of(name + "-config",
                        ConfigMap.class, (ctx, meta) -> new ConfigMapBuilder()
                                .withMetadata(meta)
                                .withData(Map.of("text", String.valueOf(ctx.primary().getSpec().getText())))
                                .build())
                .readyWhen(cm -> configReady.get() ? Readiness.ready() : Readiness.inProgress("held")));
        final Component<Widget, Deployment> deployment = builder.add(Component.<Widget, Deployment>of(name,
                        Deployment.class, (ctx, meta) -> new DeploymentBuilder()
                                .withMetadata(meta)
                                .withNewSpec()
                                .withReplicas(1)
                                .withNewSelector().addToMatchLabels("app", name).endSelector()
                                .withNewTemplate()
                                .withNewMetadata().addToLabels("app", name).endMetadata()
                                .withNewSpec().addNewContainer().withName("app").withImage("nginx").endContainer()
                                .endSpec()
                                .endTemplate()
                                .endSpec()
                                .build())
                .rolloutOn(config));
        builder.add(Component.<Widget, Service>of(name, Service.class, (ctx, meta) -> new ServiceBuilder()
                        .withMetadata(meta)
                        .withNewSpec().addToSelector("app", name).addNewPort().withPort(80).endPort().endSpec()
                        .build())
                .dependsOn(deployment));
        for (int i = 0; i < size; i++) {
            builder.add(Component.of(name + "-extra-" + i, ConfigMap.class,
                    (ctx, meta) -> new ConfigMapBuilder().withMetadata(meta).build()));
        }
        if (!"drop-keep".equals(widget.getSpec().getText())) {
            builder.add(Component.<Widget, ConfigMap>of(name + "-keep", ConfigMap.class,
                            (ctx, meta) -> new ConfigMapBuilder().withMetadata(meta).build())
                    .deletePolicy(DeletePolicy.ORPHAN));
        }
        return builder.build();
    }

    @Test
    void appliesInOrderAndReportsReadiness() {
        final String aConfig = "a-config";
        configReady.set(false);
        final Widget created = Widget.of(NS, "a", 0);
        created.getMetadata().setLabels(Map.of("team", "blue"));
        created.getSpec().setText("hello");
        client.resource(created).create();

        Await.until(() -> configMap(aConfig) != null, "the config");
        Await.until(() -> hasPhase("a", StandardStatus.PENDING), "the Pending status");
        assertNull(deployment("a"), "waits for the config to be ready");
        assertEquals("blue", configMap(aConfig).getMetadata().getLabels().get("team"), "propagated");
        assertEquals(aConfig, configMap(aConfig).getMetadata().getLabels().get(Labels.COMPONENT));

        configReady.set(true);
        operator.controller(CONTROLLER).enqueue(NS, "a");

        Await.until(() -> deployment("a") != null, "the deployment");
        // the mock runs no deployment controller: report the rollout done by hand
        markAvailable("a");
        Await.until(() -> service("a") != null, "the service, after the deployment is ready");
        Await.until(() -> hasPhase("a", StandardStatus.READY), "the Ready status");
        assertEquals(widget("a").getMetadata().getGeneration(), widget("a").getStatus().getObservedGeneration());
    }

    @Test
    void configChangeRollsThePods() {
        final Widget created = Widget.of(NS, "c", 0);
        created.getSpec().setText("one");
        client.resource(created).create();
        Await.until(() -> deployment("c") != null, "the deployment");
        final String first = rolloutHash("c");
        assertNotNull(first);

        editWidget("c", w -> w.getSpec().setText("two"));

        Await.until(() -> !first.equals(rolloutHash("c")), "a new rollout hash");
        assertEquals("two", configMap("c-config").getData().get("text"));
    }

    @Test
    void prunesWhatLeftTheCompositionAndReleasesOrphans() {
        final String dKeep = "d-keep";
        client.resource(Widget.of(NS, "d", 3)).create();
        Await.until(() -> configMap("d-extra-2") != null && configMap(dKeep) != null, "the extras");

        editWidget("d", w -> {
            w.getSpec().setSize(1);
            w.getSpec().setText("drop-keep");
        });

        Await.until(() -> configMap("d-extra-1") == null && configMap("d-extra-2") == null, "the pruning");
        assertNotNull(configMap("d-extra-0"));
        assertTrue(pruned.containsAll(List.of("ConfigMap/d-extra-1", "ConfigMap/d-extra-2")));

        Await.until(() -> !configMap(dKeep).getMetadata().getLabels().containsKey(Labels.ownerUid(DOMAIN)),
                "the orphan to be released");
        assertTrue(configMap(dKeep).getMetadata().getOwnerReferences().isEmpty());
    }

    @Test
    void cleanupDeletesInReverseDependencyOrder() {
        client.resource(Widget.of(NS, "e", 1)).create();
        Await.until(() -> deployment("e") != null, "the deployment");
        markAvailable("e");
        Await.until(() -> service("e") != null, "the service");

        client.resources(Widget.class).inNamespace(NS).withName("e").delete();

        Await.until(() -> widget("e") == null, "the widget to be gone");
        assertNull(configMap("e-config"));
        assertNull(deployment("e"));
        assertNull(service("e"));
        assertNotNull(configMap("e-keep"), "orphaned, not deleted");
        assertTrue(deleted.indexOf("Service/e") < deleted.indexOf("Deployment/e"), deleted.toString());
        assertTrue(deleted.indexOf("Deployment/e") < deleted.indexOf("ConfigMap/e-config"), deleted.toString());
    }

    @Test
    void invalidResourceGetsTheErrorStatus() {
        client.resource(Widget.of(NS, "f", -1)).create();
        Await.until(() -> hasPhase("f", StandardStatus.ERROR), "the Error status");
    }

    @Test
    void driftIsCorrected() {
        client.resource(Widget.of(NS, "g", 0)).create();
        Await.until(() -> deployment("g") != null, "the deployment");
        final String hash = rolloutHash("g");

        Conflicts.retry(() -> client.apps().deployments().inNamespace(NS).withName("g").edit(d -> {
            d.getSpec().getTemplate().getMetadata().getAnnotations().put(Rollouts.annotation(DOMAIN), "tampered");
            return d;
        }));

        Await.until(() -> hash.equals(rolloutHash("g")), "the deployment to be applied again");
        assertNotEquals("tampered", rolloutHash("g"));
    }

    private void editWidget(final String name,
                            final Consumer<Widget> change) {
        Conflicts.retry(() -> client.resources(Widget.class).inNamespace(NS).withName(name).edit(w -> {
            change.accept(w);
            return w;
        }));
    }

    private void markAvailable(final String name) {
        Await.until(() -> deployment(name) != null, "the deployment " + name);
        Conflicts.retry(() -> client.apps().deployments().inNamespace(NS).withName(name).editStatus(d -> {
            d.setStatus(new DeploymentStatusBuilder()
                    .withObservedGeneration(d.getMetadata().getGeneration())
                    .withReplicas(1).withUpdatedReplicas(1).withReadyReplicas(1).withAvailableReplicas(1)
                    .build());
            return d;
        }));
    }

    private boolean hasPhase(final String name,
                             final String phase) {
        return Fixtures.hasPhase(widget(name), phase);
    }

    private String rolloutHash(final String name) {
        final Deployment deployment = deployment(name);
        return deployment == null ? null
                : deployment.getSpec().getTemplate().getMetadata().getAnnotations().get(Rollouts.annotation(DOMAIN));
    }

    private Widget widget(final String name) {
        return client.resources(Widget.class).inNamespace(NS).withName(name).get();
    }

    private ConfigMap configMap(final String name) {
        return client.configMaps().inNamespace(NS).withName(name).get();
    }

    private Deployment deployment(final String name) {
        return client.apps().deployments().inNamespace(NS).withName(name).get();
    }

    private Service service(final String name) {
        return client.services().inNamespace(NS).withName(name).get();
    }
}
