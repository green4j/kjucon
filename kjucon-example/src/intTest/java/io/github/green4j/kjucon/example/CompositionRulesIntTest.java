/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.ControllerBuilder;
import io.github.green4j.kjucon.Labels;
import io.github.green4j.kjucon.Operator;
import io.github.green4j.kjucon.compose.ApplyErrorPolicy;
import io.github.green4j.kjucon.compose.CleanupMode;
import io.github.green4j.kjucon.compose.Composition;
import io.github.green4j.kjucon.compose.CompositionFactory;
import io.github.green4j.kjucon.compose.CompositionReconciler;
import io.github.green4j.kjucon.compose.DeletePolicy;
import io.github.green4j.kjucon.example.webapp.WebApp;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import io.github.green4j.kjucon.test.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.github.green4j.kjucon.example.K3sCluster.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of where a composition's objects may live and how they go, against a real API server - where the
 * garbage collector actually collects and a finalizer actually holds.
 */
class CompositionRulesIntTest {
    private static final String NS = "rules";
    private static final String REMOTE = "rules-remote";
    private static final String GC_NS = "rules-gc";
    private static final String SELECTED_NS = "rules-selected";
    private static final String SHARED_NS = "rules-shared";
    private static final String SHARED_REMOTE = "rules-shared-remote";
    private static final String RENAME_NS = "rules-rename";
    private static final String RENAME_REMOTE = "rules-rename-remote";
    private static final String ERRORS_NS = "rules-errors";
    private static final String LEGACY_NS = "rules-legacy";
    private static final String DOMAIN = "rules.kjucon.green4j.github.io";

    private static final List<Operator> OPERATORS = new CopyOnWriteArrayList<>();
    private static KubernetesClient client;

    @BeforeAll
    static void start() {
        client = K3sCluster.namespace(NS);
        K3sCluster.namespace(REMOTE);
        K3sCluster.namespace(GC_NS);
        K3sCluster.namespace(SELECTED_NS);
        K3sCluster.namespace(SHARED_NS);
        K3sCluster.namespace(SHARED_REMOTE);
        K3sCluster.namespace(RENAME_NS);
        K3sCluster.namespace(RENAME_REMOTE);
        K3sCluster.namespace(ERRORS_NS);
        K3sCluster.namespace(LEGACY_NS);

        // a local ConfigMap, and remote ones - the extra one only until the spec's message says "drop"
        start(builder -> builder.watchNamespaces(NS, REMOTE), "rules", CleanupMode.ORDERED, null, context -> {
            final String name = context.primary().getMetadata().getName();
            final Composition.Builder<WebApp> composition = Composition.builder();
            composition.component(name + "-local", ConfigMap.class, (ctx, meta) -> configMap(meta));
            composition.component(name + "-remote", ConfigMap.class, (ctx, meta) -> configMap(meta))
                    .namespace(REMOTE);
            if (!"drop".equals(context.primary().getSpec().getMessage())) {
                composition.component(name + "-remote-extra", ConfigMap.class, (ctx, meta) -> configMap(meta))
                        .namespace(REMOTE);
            }
            return composition.build();
        });

        start(builder -> builder.watchNamespace(GC_NS), "rules-gc", CleanupMode.GARBAGE_COLLECTOR, null,
                context -> {
                    final Composition.Builder<WebApp> composition = Composition.builder();
                    composition.component(context.primary().getMetadata().getName() + "-keep", ConfigMap.class,
                            (ctx, meta) -> configMap(meta)).deletePolicy(DeletePolicy.ORPHAN);
                    return composition.build();
                });

        start(builder -> builder.watchNamespace(SELECTED_NS), "rules-selected", CleanupMode.ORDERED,
                Map.of("pick", "me"), context -> {
                    final Composition.Builder<WebApp> composition = Composition.builder();
                    composition.component(context.primary().getMetadata().getName() + "-cfg", ConfigMap.class,
                            (ctx, meta) -> configMap(meta));
                    return composition.build();
                });

        // every resource composes the same object, in another namespace: one of them only may have it
        start(builder -> builder.watchNamespaces(SHARED_NS, SHARED_REMOTE), "rules-shared", CleanupMode.ORDERED,
                null, context -> {
                    final Composition.Builder<WebApp> composition = Composition.builder();
                    composition.component("shared", ConfigMap.class, (ctx, meta) -> configMap(meta))
                            .namespace(SHARED_REMOTE);
                    return composition.build();
                });


        // an object the API server refuses, failing alone, and another one
        start(builder -> builder.watchNamespace(ERRORS_NS), "rules-errors", CleanupMode.ORDERED, null,
                context -> {
                    final String name = context.primary().getMetadata().getName();
                    final Composition.Builder<WebApp> composition = Composition.builder();
                    composition.component(name + "-bad", ConfigMap.class, (ctx, meta) -> new ConfigMapBuilder()
                            .withMetadata(meta).addToData("not a key", "1").build())
                            .applyErrors(ApplyErrorPolicy.FAIL_COMPONENT);
                    composition.component(name + "-good", ConfigMap.class, (ctx, meta) -> configMap(meta));
                    return composition.build();
                });

        // reconciles only what is picked, so that an object can be made for a resource before
        start(builder -> builder.watchNamespace(LEGACY_NS), "rules-legacy", CleanupMode.ORDERED,
                Map.of("pick", "me"), context -> {
                    final Composition.Builder<WebApp> composition = Composition.builder();
                    composition.component(context.primary().getMetadata().getName() + "-cfg", ConfigMap.class,
                            (ctx, meta) -> configMap(meta));
                    return composition.build();
                });
    }

    @AfterAll
    static void stop() {
        for (final Operator operator : OPERATORS) {
            operator.close();
        }
    }

    @Test
    void objectsInAnotherWatchedNamespaceArePrunedAndDeleted() {
        final String cross = "cross";
        client.resource(ExampleResources.webApp(NS, cross)).create();
        Await.until(() -> configMap(REMOTE, cross + "-remote-extra") != null, TIMEOUT, "the remote objects");
        Await.until(() -> hasPhase(NS, cross, StandardStatus.READY), TIMEOUT, "Ready");

        Conflicts.retry(() -> client.resources(WebApp.class).inNamespace(NS).withName(cross).edit(w -> {
            w.getSpec().setMessage("drop");
            return w;
        }));
        Await.until(() -> configMap(REMOTE, cross + "-remote-extra") == null, TIMEOUT, "the remote prune");

        client.resources(WebApp.class).inNamespace(NS).withName(cross).delete();
        Await.until(() -> get(NS, cross) == null, TIMEOUT, "the resource to be gone");
        assertNull(configMap(REMOTE, cross + "-remote"), "deleted by the cleanup, which no garbage collector would");
    }

    @Test
    void orphanUnderTheGarbageCollectorIsRejected() {
        final String gc = "gc";
        client.resource(ExampleResources.webApp(GC_NS, gc)).create();

        Await.until(() -> hasPhase(GC_NS, gc, StandardStatus.ERROR), TIMEOUT, "the Error status");
        assertTrue(get(GC_NS, gc).getStatus().getMessage().contains("needs CleanupMode.ORDERED"),
                get(GC_NS, gc).getStatus().getMessage());
        assertNull(configMap(GC_NS, gc + "-keep"), "nothing applied");
    }

    @Test
    void resourceWhichLostTheSelectorLabelStillGoes() {
        final String sel = "sel";
        final WebApp app = ExampleResources.webApp(SELECTED_NS, sel);
        app.getMetadata().setLabels(Map.of("pick", "me"));
        client.resource(app).create();
        Await.until(() -> configMap(SELECTED_NS, sel + "-cfg") != null, TIMEOUT, "the first reconcile");

        Conflicts.retry(() -> client.resources(WebApp.class).inNamespace(SELECTED_NS).withName(sel).edit(w -> {
            w.getMetadata().getLabels().remove("pick");
            return w;
        }));
        client.resources(WebApp.class).inNamespace(SELECTED_NS).withName(sel).delete();

        Await.until(() -> get(SELECTED_NS, sel) == null, TIMEOUT, "the resource to be gone");
        assertNull(configMap(SELECTED_NS, sel + "-cfg"), "cleaned up");
    }

    @Test
    void objectOfAnotherResourceIsNotTakenOrDeleted() {
        final String first = "first";
        final String second = "second";
        client.resource(ExampleResources.webApp(SHARED_NS, first)).create();
        Await.until(() -> hasPhase(SHARED_NS, first, StandardStatus.READY), TIMEOUT, "the first owner Ready");
        final String firstUid = get(SHARED_NS, first).getMetadata().getUid();

        client.resource(ExampleResources.webApp(SHARED_NS, second)).create();
        Await.until(() -> hasPhase(SHARED_NS, second, StandardStatus.ERROR), TIMEOUT, "the second in Error");
        final String message = get(SHARED_NS, second).getStatus().getMessage();
        assertTrue(message.contains("belongs to") && message.contains(SHARED_NS + "/first"), message);
        assertEquals(firstUid, configMap(SHARED_REMOTE, "shared").getMetadata().getLabels()
                .get(DOMAIN + "/owner-uid"), "still the first owner's");

        client.resources(WebApp.class).inNamespace(SHARED_NS).withName(second).delete();
        Await.until(() -> get(SHARED_NS, second) == null, TIMEOUT, "the second to be gone");
        assertNotNull(configMap(SHARED_REMOTE, "shared"), "not deleted by the cleanup of who does not own it");
    }

    @Test
    void objectsOfTheFormerNameAreTakenOverPrunedAndDeleted() {
        final String drop = "drop";
        final String kept = "kept";
        final String early = "early";
        final String renameOld = "rename-old";
        // a local object with a key only the former name applies, a remote one, and an extra one until drop
        final CompositionFactory<WebApp> factory = context -> {
            final String name = context.primary().getMetadata().getName();
            final Composition.Builder<WebApp> composition = Composition.builder();
            composition.component(name + "-cfg", ConfigMap.class, (ctx, meta) -> {
                final ConfigMap configMap = configMap(meta);
                if (renameOld.equals(ctx.controllerName())) {
                    configMap.getData().put("old", "x");
                }
                return configMap;
            });
            composition.component(name + "-remote", ConfigMap.class, (ctx, meta) -> configMap(meta))
                    .namespace(RENAME_REMOTE);
            if (!drop.equals(context.primary().getSpec().getMessage())) {
                composition.component(name + "-extra", ConfigMap.class, (ctx, meta) -> configMap(meta));
            }
            return composition.build();
        };
        final Consumer<Operator.Builder> namespaces = builder -> builder.watchNamespaces(RENAME_NS, RENAME_REMOTE);
        final Operator old = start(namespaces, renameOld, CleanupMode.ORDERED, null, factory);
        client.resource(ExampleResources.webApp(RENAME_NS, kept)).create();
        client.resource(ExampleResources.webApp(RENAME_NS, early)).create();
        Await.until(() -> hasPhase(RENAME_NS, kept, StandardStatus.READY)
                && hasPhase(RENAME_NS, early, StandardStatus.READY), TIMEOUT, "Ready under the former name");
        OPERATORS.remove(old);
        old.close();
        // deleted before the renamed controller ever reconciled it
        client.resources(WebApp.class).inNamespace(RENAME_NS).withName(early).delete();

        start(namespaces, "rename-new", CleanupMode.ORDERED, null, factory,
                controller -> controller.formerNames(renameOld));

        Await.until(() -> get(RENAME_NS, early) == null, TIMEOUT, "the early one to be cleaned up and gone");
        assertNull(configMap(RENAME_REMOTE, early + "-remote"), "deleted, which no garbage collector would do");

        Await.until(() -> List.of(DOMAIN + "/rename-new").equals(get(RENAME_NS, kept).getMetadata().getFinalizers()),
                TIMEOUT, "the finalizer of the former name replaced");
        Await.until(() -> !configMap(RENAME_NS, kept + "-cfg").getData().containsKey("old"), TIMEOUT,
                "what only the former name applied to be removed");
        assertTrue(configMap(RENAME_NS, kept + "-cfg").getMetadata().getManagedFields().stream()
                .noneMatch(e -> renameOld.equals(e.getManager())), "the former manager is gone");
        Conflicts.retry(() -> client.resources(WebApp.class).inNamespace(RENAME_NS).withName(kept).edit(w -> {
            w.getSpec().setMessage(drop);
            return w;
        }));
        Await.until(() -> configMap(RENAME_NS, kept + "-extra") == null, TIMEOUT,
                "the prune of a former name's object");

        client.resources(WebApp.class).inNamespace(RENAME_NS).withName(kept).delete();
        Await.until(() -> get(RENAME_NS, kept) == null, TIMEOUT, "the resource to be gone");
        assertNull(configMap(RENAME_REMOTE, kept + "-remote"), "deleted by the cleanup");
    }

    @Test
    void objectTheServerRefusesFailsItsComponentOnly() {
        final String broken = "broken";
        client.resource(ExampleResources.webApp(ERRORS_NS, broken)).create();

        Await.until(() -> hasPhase(ERRORS_NS, broken, StandardStatus.ERROR), TIMEOUT, "the Error status");
        final String message = get(ERRORS_NS, broken).getStatus().getMessage();
        assertTrue(message.contains(broken + "-bad") && message.contains("not a key"), message);
        Await.until(() -> configMap(ERRORS_NS, broken + "-good") != null, TIMEOUT, "the other component applied");
        assertNull(configMap(ERRORS_NS, broken + "-bad"));
    }

    @Test
    void objectWhoseManagedByHelmTookIsAdopted() {
        final String legacy = "legacy";
        final WebApp app = client.resource(ExampleResources.webApp(LEGACY_NS, legacy)).create();
        // applied before the controller label, and relabelled by Helm since
        client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(LEGACY_NS).withName(legacy + "-cfg")
                .addToLabels(Labels.ownerUid(DOMAIN), app.getMetadata().getUid())
                .addToLabels(Labels.MANAGED_BY, "Helm")
                .endMetadata().addToData("old", "1").build()).create();

        Conflicts.retry(() -> client.resources(WebApp.class).inNamespace(LEGACY_NS).withName(legacy).edit(w -> {
            w.getMetadata().setLabels(Map.of("pick", "me"));
            return w;
        }));
        Await.until(() -> hasPhase(LEGACY_NS, legacy, StandardStatus.READY), TIMEOUT, "Ready");
        final ConfigMap adopted = configMap(LEGACY_NS, legacy + "-cfg");
        assertEquals("rules-legacy", adopted.getMetadata().getLabels().get(Labels.controller(DOMAIN)));
        assertEquals("1", adopted.getData().get("a"));

        client.resources(WebApp.class).inNamespace(LEGACY_NS).withName(legacy).delete();
        Await.until(() -> get(LEGACY_NS, legacy) == null, TIMEOUT, "the resource to be gone");
        assertNull(configMap(LEGACY_NS, legacy + "-cfg"), "its own now: cleaned up");
    }

    private static Operator start(final Consumer<Operator.Builder> namespaces,
                                  final String name,
                                  final CleanupMode mode,
                                  final Map<String, String> selector,
                                  final CompositionFactory<WebApp> factory) {
        return start(namespaces, name, mode, selector, factory, controller -> { });
    }

    private static Operator start(final Consumer<Operator.Builder> namespaces,
                                  final String name,
                                  final CleanupMode mode,
                                  final Map<String, String> selector,
                                  final CompositionFactory<WebApp> factory,
                                  final Consumer<ControllerBuilder<WebApp>> controller) {
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .labelDomain(DOMAIN);
        namespaces.accept(builder);
        final ControllerBuilder<WebApp> controllerBuilder = builder.controller(WebApp.class,
                        CompositionReconciler.builder(factory)
                                .manages(ConfigMap.class)
                                .cleanup(mode)
                                .cleanupPoll(Duration.ofMillis(200))
                                .build())
                .name(name)
                .selector(selector != null ? selector : Map.of());
        controller.accept(controllerBuilder);
        final Operator operator = builder.build();
        OPERATORS.add(operator);
        Await.started(operator, TIMEOUT);
        return operator;
    }

    private static ConfigMap configMap(final ObjectMeta meta) {
        return new ConfigMapBuilder().withMetadata(meta).addToData("a", "1").build();
    }


    private static boolean hasPhase(final String namespace,
                                    final String name,
                                    final String phase) {
        return Fixtures.hasPhase(get(namespace, name), phase);
    }

    private static WebApp get(final String namespace,
                              final String name) {
        return client.resources(WebApp.class).inNamespace(namespace).withName(name).get();
    }

    private static ConfigMap configMap(final String namespace,
                                       final String name) {
        return client.configMaps().inNamespace(namespace).withName(name).get();
    }
}
