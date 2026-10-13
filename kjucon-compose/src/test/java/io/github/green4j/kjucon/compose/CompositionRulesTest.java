/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.ControllerBuilder;
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
import io.github.green4j.kjucon.test.RecordingErrorHandler;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.green4j.kjucon.test.Fixtures.CONTROLLER;
import static io.github.green4j.kjucon.test.Fixtures.FORMER_CONTROLLER;
import static io.github.green4j.kjucon.test.Fixtures.OTHER_CONTROLLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a composition may hold, and where.
 */
class CompositionRulesTest {
    private static final String NS = Fixtures.NAMESPACE;
    private static final String OTHER = "other";
    private static final String DOMAIN = Fixtures.LABEL_DOMAIN;

    private MockKubernetes kubernetes;
    private KubernetesClient client;
    private Operator operator;
    private final RecordingErrorHandler handler = new RecordingErrorHandler();

    @BeforeEach
    void start() {
        kubernetes = new MockKubernetes();
        client = kubernetes.client();
    }

    @AfterEach
    void stop() {
        if (operator != null) {
            operator.close();
        }
        kubernetes.close();
    }

    static Stream<Arguments> invalidCompositions() {
        final Composer local = (context, builder) -> builder.component("local", ConfigMap.class,
                RulesFactories::configMap);
        return Stream.of(
                Arguments.of("an object in a namespace not watched", CleanupMode.ORDERED, local.and(
                        (context, builder) -> builder.component("remote", ConfigMap.class, RulesFactories::configMap)
                                .namespace(OTHER)), new String[] {NS}),
                Arguments.of("an orphan under the garbage collector", CleanupMode.GARBAGE_COLLECTOR, local.and(
                        (context, builder) -> builder.component("keep", ConfigMap.class, RulesFactories::configMap)
                                .deletePolicy(DeletePolicy.ORPHAN)), new String[] {NS}),
                Arguments.of("an object elsewhere under the garbage collector", CleanupMode.GARBAGE_COLLECTOR,
                        local.and((context, builder) -> builder.component("remote", ConfigMap.class,
                                RulesFactories::configMap).namespace(OTHER)), new String[] {NS, OTHER}),
                Arguments.of("a type not managed", CleanupMode.ORDERED, local.and(
                        (context, builder) -> builder.component("secret", Secret.class,
                                (ctx, meta) -> new SecretBuilder().withMetadata(meta).build())), new String[] {NS}),
                Arguments.of("a factory building another namespace", CleanupMode.ORDERED,
                        (Composer) (context, builder) -> builder.component("local", ConfigMap.class, (ctx, meta) -> {
                            meta.setNamespace(OTHER);
                            return RulesFactories.configMap(ctx, meta);
                        }), new String[] {NS, OTHER}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCompositions")
    void invalidCompositionIsRejectedBeforeAnythingIsApplied(final String what,
                                                             final CleanupMode mode,
                                                             final Composer composer,
                                                             final String[] namespaces) {
        final String widget = "invalid";
        run(mode, null, composer, namespaces);
        client.resource(Widget.of(NS, widget, 1)).create();

        Await.until(() -> phase(widget, StandardStatus.ERROR), "the Error status");
        assertNull(configMap(NS, "local"), "nothing applied");
        assertNull(configMap(OTHER, "local"), "nothing applied");
    }

    @Test
    void resourceWhichCannotBeComposedAnyMoreIsStillCleanedUp() {
        final String widget = "uncomposable";
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            if ("broken".equals(context.primary().getSpec().getText())) {
                throw new InvalidResourceException("cannot be composed");
            }
            builder.component(widget + "-local", ConfigMap.class, RulesFactories::configMap);
            builder.component(widget + "-remote", ConfigMap.class, RulesFactories::configMap).namespace(OTHER);
        }, NS, OTHER);
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> phase(widget, StandardStatus.READY), "the Ready status");

        Conflicts.retry(() -> client.resources(Widget.class).inNamespace(NS).withName(widget).edit(w -> {
            w.getSpec().setText("broken");
            return w;
        }));
        Await.until(() -> phase(widget, StandardStatus.ERROR), "the Error status");
        client.resources(Widget.class).inNamespace(NS).withName(widget).delete();

        Await.until(() -> widget(widget) == null, "the widget to be gone");
        assertNull(configMap(NS, widget + "-local"));
        assertNull(configMap(OTHER, widget + "-remote"), "found by its owner label, and deleted");
    }

    @Test
    void objectChangedWhileBeingAdoptedIsAdoptedOnTheNextPass() {
        final String widget = "adopting";
        client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(NS).withName(widget + "-cfg")
                .endMetadata().build()).create(); // nobody's
        kubernetes.refuseApply("/api/v1/namespaces/" + NS + "/configmaps/" + widget + "-cfg", 409, 1);
        run(CleanupMode.ORDERED, null, (context, builder) -> builder.component(widget + "-cfg", ConfigMap.class,
                RulesFactories::configMap), NS);
        client.resource(Widget.of(NS, widget, 1)).create();

        Await.until(() -> CONTROLLER.equals(controllerOf(widget + "-cfg")), "the adoption");
        assertFalse(handler.reconcileErrors().isEmpty(), "the first pass failed, to be retried");
    }

    @Test
    void readinessIsForgottenWhenTheResourceIsGone() {
        final String widget = "w7";
        final List<Readiness> previous = new CopyOnWriteArrayList<>();
        run(CleanupMode.GARBAGE_COLLECTOR, new CompositionObserver() {
            @Override
            public void onCompositionReadinessChanged(final ReconcileRequest primary,
                                                      final Readiness from,
                                                      final Readiness to) {
                previous.add(from);
            }
        }, (context, builder) -> builder.component(context.primary().getMetadata().getName() + "-cfg",
                ConfigMap.class, RulesFactories::configMap), NS);

        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> phase(widget, StandardStatus.READY), "the Ready status");
        client.resources(Widget.class).inNamespace(NS).withName(widget).delete();
        Await.until(() -> widget(widget) == null, "the widget to be gone");
        // the garbage collector, which the mock does not have: the new widget would not take the old one's object
        client.configMaps().inNamespace(NS).withName(widget + "-cfg").delete();
        previous.clear();

        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> !previous.isEmpty(), "the first readiness of the new widget");
        assertNull(previous.get(0), "nothing remembered of the widget of the same name before");
    }

    @Test
    void objectOfAnotherResourceIsNotTaken() {
        final String widget = "w8";
        final String otherWidget = "w9";
        final String sharedName = "shared";
        run(CleanupMode.ORDERED, null, (context, builder) -> builder.component(sharedName, ConfigMap.class,
                RulesFactories::configMap).namespace(OTHER), NS, OTHER);
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> phase(widget, StandardStatus.READY), "the Ready status of the first owner");
        final String firstUid = widget(widget).getMetadata().getUid();

        client.resource(Widget.of(NS, otherWidget, 1)).create();
        Await.until(() -> phase(otherWidget, StandardStatus.ERROR), "the Error status of the second");
        assertEquals(firstUid, configMap(OTHER, sharedName).getMetadata().getLabels().get(DOMAIN + "/owner-uid"),
                "still the first owner's");

        client.resources(Widget.class).inNamespace(NS).withName(widget).delete();
        Await.until(() -> widget(widget) == null, "the first owner to be gone");
        final String secondUid = widget(otherWidget).getMetadata().getUid();
        Await.until(() -> {
            final ConfigMap shared = configMap(OTHER, sharedName);
            return shared != null && secondUid.equals(shared.getMetadata().getLabels().get(DOMAIN + "/owner-uid"));
        }, "the second to take the object once it is free");
    }

    @Test
    void jobWithATtlFailsItsComponentOnly() {
        final String widgetName = "w10";
        final Widget widget = client.resource(Widget.of(NS, widgetName, 1)).create();
        applied(NS, widgetName + "-left", widget, CONTROLLER, null);
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            builder.component(widgetName + "-job", Job.class, (ctx, meta) -> new JobBuilder().withMetadata(meta)
                    .withNewSpec().withTtlSecondsAfterFinished(60).endSpec().build());
            builder.component(widgetName + "-cfg", ConfigMap.class, RulesFactories::configMap);
        }, NS);

        Await.until(() -> phase(widgetName, StandardStatus.ERROR), "the Error status");
        assertNull(client.resources(Job.class).inNamespace(NS).withName(widgetName + "-job").get());
        Await.until(() -> configMap(NS, widgetName + "-cfg") != null, "the other component to be applied");
        Await.until(() -> configMap(NS, widgetName + "-left") == null, "what left the composition to be pruned");
    }

    @Test
    void objectsOfTheFormerNameArePrunedAndDeleted() {
        final String widgetName = "w12";
        final Widget widget = client.resource(Widget.of(NS, widgetName, 1)).create();
        applied(NS, widgetName + "-keep", widget, FORMER_CONTROLLER, FORMER_CONTROLLER);
        applied(NS, widgetName + "-left", widget, FORMER_CONTROLLER, FORMER_CONTROLLER);
        applied(OTHER, widgetName + "-remote", widget, null, FORMER_CONTROLLER); // applied before the controller label
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            builder.component(widgetName + "-keep", ConfigMap.class, RulesFactories::configMap);
            builder.component(widgetName + "-remote", ConfigMap.class, RulesFactories::configMap).namespace(OTHER);
        }, reconciler -> { }, controller -> controller.formerNames(FORMER_CONTROLLER), NS, OTHER);

        Await.until(() -> phase(widgetName, StandardStatus.READY), "the Ready status");
        Await.until(() -> configMap(NS, widgetName + "-left") == null, "the object of the former name to be pruned");
        assertEquals(CONTROLLER, controllerOf(widgetName + "-keep"));

        client.resources(Widget.class).inNamespace(NS).withName(widgetName).delete();
        Await.until(() -> widget(widgetName) == null, "the widget to be gone");
        assertNull(configMap(OTHER, widgetName + "-remote"), "deleted by the cleanup");
    }

    @Test
    void resourceDeletedBeforeTheRenamedControllerSawItIsCleanedUp() {
        final String widgetName = "w13";
        final Widget held = Widget.of(NS, widgetName, 1);
        held.getMetadata().setFinalizers(List.of(DOMAIN + '/' + FORMER_CONTROLLER));
        final Widget widget = client.resource(held).create();
        applied(NS, widgetName + "-cfg", widget, FORMER_CONTROLLER, FORMER_CONTROLLER);
        applied(OTHER, widgetName + "-remote", widget, null, FORMER_CONTROLLER);
        client.resources(Widget.class).inNamespace(NS).withName(widgetName).delete();

        run(CleanupMode.ORDERED, null, (context, builder) -> {
            builder.component(widgetName + "-cfg", ConfigMap.class, RulesFactories::configMap);
            builder.component(widgetName + "-remote", ConfigMap.class, RulesFactories::configMap).namespace(OTHER);
        }, reconciler -> { }, controller -> controller.formerNames(FORMER_CONTROLLER), NS, OTHER);

        Await.until(() -> widget(widgetName) == null, "the widget to be gone");
        assertNull(configMap(NS, widgetName + "-cfg"));
        assertNull(configMap(OTHER, widgetName + "-remote"), "an object the garbage collector would never delete");
    }

    @Test
    void objectOfAnotherControllerOfTheResourceIsNeitherTakenNorPruned() {
        final String widgetName = "w14";
        final Widget widget = client.resource(Widget.of(NS, widgetName, 1)).create();
        applied(NS, widgetName + "-shared", widget, OTHER_CONTROLLER, OTHER_CONTROLLER);
        applied(NS, widgetName + "-theirs", widget, OTHER_CONTROLLER, OTHER_CONTROLLER);
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            builder.component(widgetName + "-shared", ConfigMap.class, RulesFactories::configMap);
            builder.component(widgetName + "-cfg", ConfigMap.class, RulesFactories::configMap);
        }, NS);

        Await.until(() -> phase(widgetName, StandardStatus.ERROR), "the Error status");
        Await.until(() -> configMap(NS, widgetName + "-cfg") != null, "the other component to be applied");
        assertEquals(OTHER_CONTROLLER, controllerOf(widgetName + "-shared"));
        assertTrue(configMap(NS, widgetName + "-theirs") != null, "not pruned: not this controller's");
    }

    @Test
    void managedByLabelCanBeLeftToOthers() {
        final String widget = "w15";
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            if (!"release".equals(context.primary().getSpec().getText())) {
                builder.component(widget + "-cfg", ConfigMap.class, RulesFactories::configMap)
                        .deletePolicy(DeletePolicy.ORPHAN);
            }
        }, reconciler -> { }, controller -> controller.managedByLabel(false), NS);
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> phase(widget, StandardStatus.READY), "the Ready status");
        final Map<String, String> labels = configMap(NS, widget + "-cfg").getMetadata().getLabels();
        assertNull(labels.get(Labels.MANAGED_BY));
        assertEquals(CONTROLLER, labels.get(Labels.controller(DOMAIN)));

        client.configMaps().inNamespace(NS).withName(widget + "-cfg").edit(c -> {
            c.getMetadata().getLabels().put(Labels.MANAGED_BY, "Helm");
            return c;
        });
        Conflicts.retry(() -> client.resources(Widget.class).inNamespace(NS).withName(widget).edit(w -> {
            w.getSpec().setText("release");
            return w;
        }));
        Await.until(() -> !configMap(NS, widget + "-cfg").getMetadata().getLabels()
                        .containsKey(Labels.ownerUid(DOMAIN)),
                "the release");
        assertEquals("Helm", configMap(NS, widget + "-cfg").getMetadata().getLabels().get(Labels.MANAGED_BY),
                "somebody else's label is kept");
    }

    @ParameterizedTest(name = "claimed for {0} ms: taken by the second {1}")
    @CsvSource({"60000, false", "200, true"})
    void objectJustAppliedIsHeldForItsResourceForTheClaimWindowOnly(final long claimWindowMillis,
                                                                    final boolean taken) {
        runSharing(Duration.ofMillis(claimWindowMillis));
        final String second = "w17";
        if (taken) {
            final String secondUid = widget(second).getMetadata().getUid();
            Await.until(() -> secondUid.equals(configMap(OTHER, "shared").getMetadata().getLabels()
                    .get(Labels.ownerUid(DOMAIN))), "the second to take the object");
        } else {
            Await.until(() -> phase(second, StandardStatus.ERROR), "the Error status of the second");
        }
    }

    // The first widget applies an object; the object then looks like nobody's, as to a cache not caught up yet.
    private void runSharing(final Duration claimWindow) {
        run(CleanupMode.ORDERED, null, (context, builder) -> builder.component("shared", ConfigMap.class,
                        RulesFactories::configMap).namespace(OTHER),
                reconciler -> reconciler.claimWindow(claimWindow), controller -> { }, NS, OTHER);
        client.resource(Widget.of(NS, "w16", 1)).create();
        Await.until(() -> phase("w16", StandardStatus.READY), "the Ready status of the first");
        client.configMaps().inNamespace(OTHER).withName("shared").edit(c -> {
            c.getMetadata().setLabels(new HashMap<>());
            c.getMetadata().setAnnotations(new HashMap<>());
            return c;
        });
        client.resource(Widget.of(NS, "w17", 1)).create();
    }

    @ParameterizedTest
    @EnumSource(DeletePolicy.class)
    void objectLetGoIsFreeForAnotherResourceAtOnce(final DeletePolicy policy) {
        runDropping(policy);
        if (policy == DeletePolicy.DELETE) {
            Await.until(() -> configMap(OTHER, "shared") == null, "the object to be pruned");
        } else {
            Await.until(() -> !configMap(OTHER, "shared").getMetadata().getLabels()
                    .containsKey(Labels.ownerUid(DOMAIN)), "the release");
            final ObjectMeta released = configMap(OTHER, "shared").getMetadata();
            assertFalse(released.getLabels().containsKey(Labels.PART_OF));
            assertFalse(released.getLabels().containsKey(Labels.COMPONENT));
            assertFalse(released.getAnnotations().containsKey(DOMAIN + "/delete-policy-orphan"));
        }
        assertTakenBy("w19");
    }

    // The first widget applies an object and drops it; claims last longer than the test waits.
    private void runDropping(final DeletePolicy policy) {
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            if (!"drop".equals(context.primary().getSpec().getText())) {
                builder.component("shared", ConfigMap.class, RulesFactories::configMap).namespace(OTHER)
                        .deletePolicy(policy);
            }
        }, reconciler -> reconciler.claimWindow(Duration.ofMinutes(10)), controller -> { }, NS, OTHER);
        client.resource(Widget.of(NS, "w18", 1)).create();
        Await.until(() -> phase("w18", StandardStatus.READY), "the Ready status of the first");
        Conflicts.retry(() -> client.resources(Widget.class).inNamespace(NS).withName("w18").edit(w -> {
            w.getSpec().setText("drop");
            return w;
        }));
    }

    private void assertTakenBy(final String name) {
        client.resource(Widget.of(NS, name, 1)).create();
        Await.until(() -> phase(name, StandardStatus.READY), "the Ready status of the second");
        assertEquals(widget(name).getMetadata().getUid(),
                configMap(OTHER, "shared").getMetadata().getLabels().get(Labels.ownerUid(DOMAIN)));
    }

    @Test
    void objectWhoseManagedByAnotherToolTookIsAdoptedNotRefused() {
        final String widgetName = "w20";
        final Widget widget = client.resource(Widget.of(NS, widgetName, 1)).create();
        applied(NS, widgetName + "-cfg", widget, null, "Helm"); // applied before the controller label, then relabelled
        applied(NS, widgetName + "-left", widget, null, "Helm");
        run(CleanupMode.ORDERED, null, (context, builder) -> builder.component(widgetName + "-cfg", ConfigMap.class,
                RulesFactories::configMap), NS);

        Await.until(() -> phase(widgetName, StandardStatus.READY), "the Ready status");
        assertEquals(CONTROLLER, controllerOf(widgetName + "-cfg"));
        assertTrue(configMap(NS, widgetName + "-left") != null, "nobody's until adopted: not pruned");
    }

    static Stream<Arguments> applyErrorPolicies() {
        return Stream.of(
                // of the reconciler, of the component: whether the composition stops
                Arguments.of(null, null, true),
                Arguments.of(ApplyErrorPolicy.FAIL_COMPONENT, null, false),
                Arguments.of(null, ApplyErrorPolicy.FAIL_COMPONENT, false),
                Arguments.of(ApplyErrorPolicy.FAIL_COMPONENT, ApplyErrorPolicy.STOP_COMPOSITION, true));
    }

    @ParameterizedTest(name = "reconciler {0}, component {1}: stops {2}")
    @MethodSource("applyErrorPolicies")
    void refusedApplyStopsTheCompositionOrFailsItsComponent(final ApplyErrorPolicy reconcilerPolicy,
                                                           final ApplyErrorPolicy componentPolicy,
                                                           final boolean stops) {
        final String widget = "refused";
        runRefused(widget, reconciler -> {
            if (reconcilerPolicy != null) {
                reconciler.applyErrors(reconcilerPolicy);
            }
        }, componentPolicy);
        if (stops) {
            assertStopped(widget);
        } else {
            assertFailedAlone(widget);
        }
    }

    // A widget whose first component the API server refuses, with a second one and one which left the composition.
    private void runRefused(final String name,
                            final Consumer<CompositionReconciler.Builder<Widget>> reconcilerConfig,
                            final ApplyErrorPolicy componentPolicy) {
        final Widget widget = client.resource(Widget.of(NS, name, 1)).create();
        applied(NS, name + "-left", widget, CONTROLLER, CONTROLLER);
        kubernetes.refuseApply("/api/v1/namespaces/" + NS + "/configmaps/" + name + "-bad", 422);
        run(CleanupMode.ORDERED, null, (context, builder) -> {
            builder.component(name + "-bad", ConfigMap.class, RulesFactories::configMap)
                    .applyErrors(componentPolicy);
            builder.component(name + "-good", ConfigMap.class, RulesFactories::configMap);
        }, reconcilerConfig, controller -> { }, NS);
        Await.until(() -> phase(name, StandardStatus.ERROR), "the Error status");
        Await.until(() -> !handler.reconcileErrors().isEmpty(), "the error handler to hear of it");
    }

    private void assertStopped(final String name) {
        assertNull(configMap(NS, name + "-good"), "not applied after the refused one");
        assertTrue(configMap(NS, name + "-left") != null, "not pruned");
    }

    private void assertFailedAlone(final String name) {
        Await.until(() -> configMap(NS, name + "-good") != null, "the other component to be applied");
        Await.until(() -> configMap(NS, name + "-left") == null, "what left the composition to be pruned");
    }

    @Test
    void garbageCollectorModeLetsGoOfTheOrderedFinalizer() {
        final String widget = "w11";
        final Widget held = Widget.of(NS, widget, 1);
        held.getMetadata().setFinalizers(List.of(DOMAIN + '/' + CONTROLLER));
        client.resource(held).create();
        run(CleanupMode.GARBAGE_COLLECTOR, null, (context, builder) -> builder.component(
                context.primary().getMetadata().getName() + "-cfg", ConfigMap.class, RulesFactories::configMap), NS);

        Await.until(() -> phase(widget, StandardStatus.READY), "the Ready status");
        Await.until(() -> widget(widget).getMetadata().getFinalizers() == null
                || widget(widget).getMetadata().getFinalizers().isEmpty(), "the former finalizer to go");
        assertFalse(operator.controller(CONTROLLER).formerFinalizers().isEmpty());
    }

    private void run(final CleanupMode mode,
                     final CompositionObserver observer,
                     final Composer composer,
                     final String... namespaces) {
        run(mode, observer, composer, reconciler -> { }, controller -> { }, namespaces);
    }

    private void run(final CleanupMode mode,
                     final CompositionObserver observer,
                     final Composer composer,
                     final Consumer<CompositionReconciler.Builder<Widget>> reconcilerConfig,
                     final Consumer<ControllerBuilder<Widget>> controllerConfig,
                     final String... namespaces) {
        final CompositionReconciler.Builder<Widget> reconciler = CompositionReconciler.<Widget>builder(context -> {
            final Composition.Builder<Widget> composition = Composition.builder();
            composer.compose(context, composition);
            return composition.build();
        })
                .manages(ConfigMap.class, Job.class)
                .cleanup(mode)
                .cleanupPoll(Duration.ofMillis(100))
                .observer(observer);
        reconcilerConfig.accept(reconciler);
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespaces(namespaces)
                .labelDomain(DOMAIN)
                .errorHandler(handler);
        final ControllerBuilder<Widget> controller = builder.controller(Widget.class,
                reconciler.build()).name(CONTROLLER);
        controllerConfig.accept(controller);
        operator = builder.build();
        Await.started(operator);
    }

    // an object as the controller under the given name applied it for the widget
    private ConfigMap applied(final String namespace,
                              final String name,
                              final Widget owner,
                              final String controller,
                              final String managedBy) {
        return client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(namespace).withName(name)
                .withLabels(Fixtures.ownerLabels(owner.getMetadata().getUid(), controller, managedBy))
                .addToAnnotations(Labels.owner(DOMAIN), NS + '/' + owner.getMetadata().getName())
                .endMetadata().build()).create();
    }

    // the controller label of an object in the namespace of the widgets
    private String controllerOf(final String name) {
        return configMap(NS, name).getMetadata().getLabels().get(Labels.controller(DOMAIN));
    }

    private boolean phase(final String name,
                          final String expected) {
        return Fixtures.hasPhase(widget(name), expected);
    }

    private Widget widget(final String name) {
        return client.resources(Widget.class).inNamespace(NS).withName(name).get();
    }

    private ConfigMap configMap(final String namespace,
                                final String name) {
        return client.configMaps().inNamespace(namespace).withName(name).get();
    }

    @FunctionalInterface
    private interface Composer {
        void compose(ReconcileContext<Widget> context,
                     Composition.Builder<Widget> builder);

        default Composer and(final Composer other) {
            return (context, builder) -> {
                compose(context, builder);
                other.compose(context, builder);
            };
        }
    }

    private static final class RulesFactories {
        private RulesFactories() {
        }

        static ConfigMap configMap(final ReconcileContext<Widget> context,
                                   final ObjectMeta meta) {
            return new ConfigMapBuilder().withMetadata(meta).build();
        }
    }
}
