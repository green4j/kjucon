/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.ControllerObserver.Cause;
import io.github.green4j.kjucon.ControllerObserver.Outcome;
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
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.green4j.kjucon.test.Fixtures.CONTROLLER;
import static io.github.green4j.kjucon.test.Fixtures.OTHER_CONTROLLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerTest {
    private static final String NS = Fixtures.NAMESPACE;
    private static final String DOMAIN = Fixtures.LABEL_DOMAIN;
    private static final String FINALIZER = DOMAIN + '/' + CONTROLLER;

    private MockKubernetes kubernetes;
    private KubernetesClient client;
    private Operator operator;

    private final WidgetReconciler reconciler = new WidgetReconciler();
    private final RecordingObserver observer = new RecordingObserver();
    private final RecordingErrorHandler handler = new RecordingErrorHandler();
    private final List<Operator> extraOperators = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() {
        kubernetes = new MockKubernetes();
        client = kubernetes.client();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(NS)
                .labelDomain(DOMAIN)
                .observer(observer)
                .errorHandler(handler);
        builder.controller(Widget.class, reconciler)
                .name(CONTROLLER)
                .owns(ConfigMap.class)
                .retry(RateLimiter.fixed(Duration.ofMillis(50)));
        operator = builder.build();
        Await.started(operator);
    }

    @AfterEach
    void stop() {
        for (final Operator extra : extraOperators) {
            extra.close();
        }
        operator.close();
        kubernetes.close();
    }

    @Test
    void reconcilesWithFinalizerOwnedObjectAndStatus() {
        final String widget = "w1";
        client.resource(Widget.of(NS, widget, 3)).create();

        Await.until(() -> configMap(widget) != null, "the owned ConfigMap");
        final ConfigMap cm = configMap(widget);
        assertEquals("3", cm.getData().get("size"));
        assertEquals(CONTROLLER, cm.getMetadata().getLabels().get(Labels.MANAGED_BY));
        assertEquals(widget, cm.getMetadata().getLabels().get(Labels.PART_OF));
        assertEquals(widget(widget).getMetadata().getUid(), cm.getMetadata().getLabels().get(Labels.ownerUid(DOMAIN)));
        assertEquals(widget, cm.getMetadata().getOwnerReferences().get(0).getName());
        assertTrue(cm.getMetadata().getOwnerReferences().get(0).getController());

        Await.until(() -> widget(widget).getStatus() != null
                && widget(widget).getStatus().hasPhase(StandardStatus.READY), "the Ready status");
        assertTrue(widget(widget).getMetadata().getFinalizers().contains(FINALIZER));
        assertTrue(observer.finalizersAdded.get() >= 1);
    }

    @Test
    void changeOfAnOwnedObjectWakesTheOwner() {
        final String widget = "w3";
        client.resource(Widget.of(NS, widget, 2)).create();
        Await.until(() -> configMap(widget) != null, "the owned ConfigMap");
        Await.until(() -> observer.finished.getOrDefault(widget, 0) >= 1, "the first reconcile to finish");

        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(widget + "-config").edit(cm -> {
            cm.getData().put("size", "tampered");
            return cm;
        }));

        Await.until(() -> "2".equals(configMap(widget).getData().get("size")), "the drift to be corrected");
        assertTrue(observer.causes.contains(Cause.SECONDARY_EVENT));
    }

    @Test
    void failureIsReportedAndRetried() {
        reconciler.failuresLeft.set(2);
        client.resource(Widget.of(NS, "w4", 1)).create();

        Await.until(() -> configMap("w4") != null, "the reconcile to succeed after the failures");
        assertEquals(2, handler.reconcileErrors().size());
        assertTrue(observer.outcomes.contains(Outcome.ERROR));
        assertTrue(observer.causes.contains(Cause.RETRY) || observer.retries.get() >= 2);
    }

    @Test
    void invalidResourceIsNotRetried() throws InterruptedException {
        client.resource(Widget.of(NS, "w5", -1)).create();

        Await.until(() -> handler.reconcileFailures().size() == 1, "the rejection");
        Thread.sleep(300);
        assertEquals(1, handler.reconcileFailures().size(), "no retries");
        assertEquals(1, reconciler.reconciles.getOrDefault("w5", 0));
        assertTrue(observer.outcomes.contains(Outcome.INVALID));
    }

    @Test
    void deletionRunsCleanupAndReleasesTheFinalizer() {
        final String widget = "w6";
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> configMap(widget) != null, "the owned ConfigMap");

        client.resources(Widget.class).inNamespace(NS).withName(widget).delete();

        Await.until(() -> widget(widget) == null, "the widget to be gone");
        assertTrue(reconciler.cleanups.getOrDefault(widget, 0) >= 1,
                "cleaned up - at least once: a pass on a cache still showing the finalizer may repeat it");
        assertNull(configMap(widget), "deleted by the cleanup");
    }

    @Test
    void externalEnqueueReconciles() {
        final String widget = "w7";
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> reconciler.reconciles.getOrDefault(widget, 0) >= 1, "the first reconcile");
        final int before = reconciler.reconciles.get(widget);

        // a request already waiting in the queue absorbs the enqueue - so enqueue until one is taken as such
        Await.until(() -> {
            operator.controller(CONTROLLER).enqueue(NS, widget);
            return observer.causes.contains(Cause.EXTERNAL_EVENT);
        }, "an external request to be queued");
        Await.until(() -> reconciler.reconciles.get(widget) > before, "the requested reconcile");
    }

    @Test
    void resourceBeingDeletedIsCleanedUpEvenIfNoLongerSelected() {
        final String s1 = "s1";
        final String ns = "selected";
        final WidgetReconciler selectedReconciler = new WidgetReconciler();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN)
                .errorHandler(handler);
        builder.controller(Widget.class, selectedReconciler)
                .name("selected-widgets")
                .selector(Map.of("pick", "me"));
        final Operator selected = start(builder);

        final Widget widget = Widget.of(ns, s1, 1);
        widget.getMetadata().setLabels(Map.of("pick", "me"));
        client.resource(widget).create();
        Await.until(() -> selectedReconciler.reconciles.getOrDefault(s1, 0) >= 1, "the first reconcile");

        Conflicts.retry(() -> client.resources(Widget.class).inNamespace(ns).withName(s1).edit(w -> {
            w.getMetadata().setLabels(Map.of());
            return w;
        }));
        client.resources(Widget.class).inNamespace(ns).withName(s1).delete();

        Await.until(() -> client.resources(Widget.class).inNamespace(ns).withName(s1).get() == null,
                "the deselected widget to be gone");
        assertTrue(selectedReconciler.cleanups.getOrDefault(s1, 0) >= 1,
                "cleaned up - at least once: a pass on a cache still showing the finalizer may repeat it");
        assertTrue(selected.health().isLive());
    }

    @Test
    void formerFinalizerIsReplacedOnALiveResource() {
        final String ns = "former-live";
        final Widget widget = Widget.of(ns, "f1", 1);
        widget.getMetadata().setFinalizers(List.of(DOMAIN + "/old-widgets", "others.example.com/keep"));
        client.resource(widget).create();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN);
        builder.controller(Widget.class, new WidgetReconciler())
                .name("new-widgets")
                .formerFinalizers(DOMAIN + "/old-widgets");
        start(builder);

        Await.until(() -> List.of("others.example.com/keep", DOMAIN + "/new-widgets").equals(
                client.resources(Widget.class).inNamespace(ns).withName("f1").get().getMetadata().getFinalizers()),
                "the former finalizer replaced, the one of others kept");
    }

    @Test
    void resourceHeldByAFormerFinalizerIsCleanedUpAndLetGo() {
        final String f2 = "f2";
        final String ns = "former-deleting";
        final Widget widget = Widget.of(ns, f2, 1);
        widget.getMetadata().setFinalizers(List.of(DOMAIN + "/old-widgets"));
        client.resource(widget).create();
        client.resources(Widget.class).inNamespace(ns).withName(f2).delete();
        final WidgetReconciler formerReconciler = new WidgetReconciler();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN);
        builder.controller(Widget.class, formerReconciler)
                .name("new-widgets")
                .finalizer(false)
                .formerFinalizers(DOMAIN + "/old-widgets");
        start(builder);

        Await.until(() -> client.resources(Widget.class).inNamespace(ns).withName(f2).get() == null,
                "the widget to be gone");
        assertTrue(formerReconciler.cleanups.getOrDefault(f2, 0) >= 1,
                "cleaned up - at least once: a pass on a cache still showing the finalizer may repeat it");
    }

    @Test
    void reconcilerConfiguresItsControllerFirstAndIsClosedWithTheOperator() {
        final AtomicInteger closed = new AtomicInteger();
        final Reconciler<Widget> configuring = new Reconciler<>() {
            @Override
            public void configure(final ControllerBuilder<Widget> controller) {
                controller.name("configured").finalizer(false);
            }

            @Override
            public ReconcileResult reconcile(final ReconcileContext<Widget> context) {
                return ReconcileResult.success();
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        final Operator.Builder builder = Operator.builder().client(client).watchNamespace(NS);
        builder.controller(Widget.class, configuring);
        builder.controller(Widget.class, configuring).name("renamed"); // what the caller sets wins
        final Operator configured = builder.build();
        assertNotNull(configured.controller("configured"));
        assertNotNull(configured.controller("renamed"));
        configured.start();
        assertEquals(0, closed.get(), "not before the operator is closed");
        configured.close();
        assertEquals(2, closed.get(), "once per controller it was given to");
        configured.close();
        assertEquals(2, closed.get(), "an operator is closed once");
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of("a controller name which is no label value",
                        (Consumer<Operator.Builder>) b -> b.controller(Widget.class, new WidgetReconciler())
                                .name("Widgets of mine!")),
                Arguments.of("a former name which is no label value",
                        (Consumer<Operator.Builder>) b -> b.controller(Widget.class, new WidgetReconciler())
                                .formerNames("Widgets of mine!")),
                Arguments.of("a former finalizer without a domain",
                        (Consumer<Operator.Builder>) b -> b.controller(Widget.class, new WidgetReconciler())
                                .formerFinalizers("no-domain")),
                Arguments.of("a former name another controller has now",
                        (Consumer<Operator.Builder>) b -> {
                            b.controller(Widget.class, new WidgetReconciler()).name(CONTROLLER)
                                    .formerNames(OTHER_CONTROLLER);
                            b.controller(Widget.class, new WidgetReconciler()).name(OTHER_CONTROLLER)
                                    .finalizer(false);
                        }),
                Arguments.of("two controllers of one name", (Consumer<Operator.Builder>) b -> {
                    b.controller(Widget.class, new WidgetReconciler()).name(CONTROLLER);
                    b.controller(Widget.class, new WidgetReconciler()).name(CONTROLLER);
                }),
                Arguments.of("a label domain which is no DNS subdomain",
                        (Consumer<Operator.Builder>) b -> b.labelDomain("example..com")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void invalidConfigurationIsRejected(final String what,
                                        final Consumer<Operator.Builder> configuration) {
        final Operator.Builder builder = Operator.builder().client(client).watchNamespace(NS);
        assertThrows(IllegalArgumentException.class, () -> {
            configuration.accept(builder);
            builder.build();
        });
    }

    // within the attempts of one pass, and beyond them - the request is then repeated
    @ParameterizedTest(name = "refused {0} times, on {1}")
    @CsvSource({"2, add", "3, add", "3, remove"})
    void finalizerPatchRacingAnotherWriterIsRepeated(final int refusals,
                                                     final String patch) {
        final String widget = "raced-" + patch + refusals;
        final String path = "/apis/" + HasMetadata.getApiVersion(Widget.class) + "/namespaces/" + NS
                + "/widgets/" + widget;
        if ("add".equals(patch)) {
            kubernetes.refusePatch(path, 409, refusals); // a race for the resourceVersion
        }
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> configMap(widget) != null, "the reconcile");
        assertTrue(widget(widget).getMetadata().getFinalizers().contains(FINALIZER));

        if ("remove".equals(patch)) {
            kubernetes.refusePatch(path, 409, refusals);
            client.resources(Widget.class).inNamespace(NS).withName(widget).delete();
            Await.until(() -> widget(widget) == null, "the finalizer to go and the widget with it");
        }
        assertTrue(handler.reconcileErrors().isEmpty(), "a race is no failure");
    }

    static Stream<Arguments> wakeUps() {
        return Stream.of(
                Arguments.of(Cause.REQUEUE, (BiConsumer<ControllerBuilder<Widget>, AtomicBoolean>)
                        (controller, requeue) -> requeue.set(true)),
                Arguments.of(Cause.RESYNC, (BiConsumer<ControllerBuilder<Widget>, AtomicBoolean>)
                        (controller, requeue) -> controller.resyncPeriod(Duration.ofMillis(100))),
                Arguments.of(Cause.EXTERNAL_EVENT, (BiConsumer<ControllerBuilder<Widget>, AtomicBoolean>)
                        (controller, requeue) -> controller.eventSource(new Ticker(NS, "woken"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wakeUps")
    void resourceIsReconciledAgainWithoutAChange(final Cause cause,
                                                 final BiConsumer<ControllerBuilder<Widget>, AtomicBoolean> setup) {
        final String widget = "woken";
        final AtomicBoolean requeue = new AtomicBoolean();
        final AtomicInteger reconciles = new AtomicInteger();
        final RecordingObserver woken = new RecordingObserver();
        final Operator.Builder builder = Operator.builder().client(client).watchNamespace(NS).labelDomain(DOMAIN)
                .observer(woken);
        setup.accept(builder.controller(Widget.class, context -> {
            reconciles.incrementAndGet();
            return requeue.getAndSet(false) ? ReconcileResult.requeue() : ReconcileResult.success();
        }).name("woken").finalizer(false), requeue);
        extraOperators.add(Await.started(builder.build()));
        client.resource(Widget.of(NS, widget, 1)).create();

        Await.until(() -> woken.causes.contains(cause) && reconciles.get() >= 2, "a reconcile for " + cause);
    }

    @Test
    void readsFromTheCacheAndTheApiServer() {
        final String none = "none";
        final String creds = "creds";
        client.resource(new SecretBuilder().withNewMetadata().withNamespace(NS).withName(creds).endMetadata()
                .addToData("user", Base64.getEncoder().encodeToString("admin".getBytes(StandardCharsets.UTF_8)))
                .addToStringData("token", "t0k3n")
                .build()).create();
        final Map<String, String> picked = Map.of("pick", "me");
        client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(NS).withName("picked")
                .withLabels(picked).endMetadata().build()).create();
        final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        final Map<String, Integer> listed = new ConcurrentHashMap<>();
        final Operator.Builder builder = Operator.builder().client(client).watchNamespace(NS).labelDomain(DOMAIN);
        builder.controller(Widget.class, context -> {
            seen.put(creds, context.secretValues(NS, creds));
            seen.put(none, context.secretValues(NS, none) == null ? Map.of() : Map.of("unexpected", ""));
            listed.put("cached", context.list(ConfigMap.class, picked).size()); // owned: informed
            listed.put("live", context.list(Secret.class, picked).size());      // not informed
            return ReconcileResult.success();
        }).name("secrets").finalizer(false).owns(ConfigMap.class);
        final Operator reading = builder.build();
        extraOperators.add(reading);
        reading.start();
        client.resource(Widget.of(NS, "w-secret", 1)).create();

        Await.until(() -> seen.size() == 2 && listed.size() == 2, "the reconcile");
        assertEquals("admin", seen.get(creds).get("user"));
        assertEquals("t0k3n", seen.get(creds).get("token"));
        assertTrue(seen.get(none).isEmpty(), "null for a missing Secret");
        assertEquals(Map.of("cached", 1, "live", 0), listed);
    }

    @Test
    void unchangedStatusIsNeitherReadNorWritten() {
        final String widget = "w8";
        client.resource(Widget.of(NS, widget, 1)).create();
        Await.until(() -> widget(widget).getStatus() != null
                && widget(widget).getStatus().hasPhase(StandardStatus.READY), "the Ready status");
        Await.until(() -> observer.finished.getOrDefault(widget, 0) >= 2, "the reconcile of the status change");
        final String path = "/apis/" + HasMetadata.getApiVersion(Widget.class) + "/namespaces/" + NS
                + "/widgets/w8";
        final int reads = kubernetes.gets(path);
        final int finished = observer.finished.get(widget);

        Await.until(() -> {
            operator.controller(CONTROLLER).enqueue(NS, widget);
            return observer.finished.get(widget) >= finished + 2;
        }, "more reconciles");

        assertEquals(reads, kubernetes.gets(path), "no fresh read for a status which did not change");
    }

    @Test
    void failingObserverIsReportedAndBreaksNothing() {
        final String ns = "observed";
        final List<String> reported = new CopyOnWriteArrayList<>();
        final WidgetReconciler observedReconciler = new WidgetReconciler();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN)
                .observer(new ControllerObserver() {
                    @Override
                    public void onReconcileStarted(final String controller,
                                                   final ReconcileRequest request) {
                        throw new IllegalStateException("observer down");
                    }
                })
                .errorHandler(new ErrorHandler() {
                    @Override
                    public void onReconcileError(final String controller,
                                                 final ReconcileRequest request,
                                                 final Phase phase,
                                                 final int attempt,
                                                 final Throwable error) {
                        throw new IllegalStateException("handler down");
                    }

                    @Override
                    public void onError(final String source,
                                        final String message,
                                        final Throwable error) {
                        reported.add(source + ": " + message + ": " + error.getMessage());
                        throw new IllegalStateException("handler down");
                    }
                });
        builder.controller(Widget.class, observedReconciler).name("observed-widgets");
        start(builder);

        client.resource(Widget.of(ns, "o1", 1)).create();

        Await.until(() -> client.configMaps().inNamespace(ns).withName("o1-config").get() != null,
                "the reconcile despite the observer and the handler");
        assertFalse(reported.isEmpty(), "the observer's failure reported");
    }

    @Test
    void closeIsBoundedWhenAReconcileDoesNotStop() throws InterruptedException {
        final String ns = "stuck";
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN)
                .errorHandler(handler);
        builder.controller(Widget.class, context -> {
            entered.countDown();
            while (!release.get()) {
                try {
                    Thread.sleep(10);
                } catch (final InterruptedException ignored) {
                    // a reconcile deaf to interrupts
                }
            }
            return ReconcileResult.success();
        }).name("stuck-widgets").finalizer(false);
        final Operator stuck = start(builder);
        client.resource(Widget.of(ns, "x1", 1)).create();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the reconcile to begin");

        final long started = System.nanoTime();
        stuck.close();
        final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.set(true);

        assertTrue(millis < 8_000, "closed in " + millis + " ms");
        assertFalse(handler.errors().isEmpty(), "the workers left behind reported");
    }

    @Test
    void writeOfTheResourceDuringCleanupDoesNotFailTheFinalizerPatch() {
        final String c1 = "c1";
        final String ns = "conflict";
        final AtomicInteger cleanups = new AtomicInteger();
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(ns)
                .labelDomain(DOMAIN)
                .errorHandler(handler);
        builder.controller(Widget.class, new Reconciler<Widget>() {
            @Override
            public ReconcileResult reconcile(final ReconcileContext<Widget> context) {
                return ReconcileResult.success();
            }

            @Override
            public ReconcileResult cleanup(final ReconcileContext<Widget> context) {
                cleanups.incrementAndGet();
                // a write of the resource behind the context's back, as a status write is: the version the
                // finalizer patch is conditional on is stale now
                // (a cleanup may run once more on a cache which still has the resource it already released)
                if (client.resources(Widget.class).inNamespace(ns).withName(context.request().name()).get() != null) {
                    Conflicts.retry(() -> client.resources(Widget.class).inNamespace(ns)
                            .withName(context.request().name()).edit(w -> {
                                w.getMetadata().getAnnotations().put("touched", String.valueOf(cleanups.get()));
                                return w;
                            }));
                }
                return ReconcileResult.success();
            }
        }).name("conflicting-widgets");
        start(builder);

        client.resource(Widget.of(ns, c1, 1)).create();
        final String ours = DOMAIN + "/conflicting-widgets";
        Await.until(() -> {
            final Widget w = client.resources(Widget.class).inNamespace(ns).withName(c1).get();
            return w.getMetadata().getFinalizers() != null && w.getMetadata().getFinalizers().contains(ours);
        }, "the finalizer");

        client.resources(Widget.class).inNamespace(ns).withName(c1).delete();

        Await.until(() -> client.resources(Widget.class).inNamespace(ns).withName(c1).get() == null,
                "the widget to be gone");
        assertTrue(cleanups.get() >= 1);
        assertEquals(List.of(), handler.reconcileFailures(),
                "a write of something else does not fail the finalizer patch");
    }

    private Operator start(final Operator.Builder builder) {
        final Operator started = builder.build();
        extraOperators.add(started);
        Await.started(started);
        return started;
    }

    private Widget widget(final String name) {
        return client.resources(Widget.class).inNamespace(NS).withName(name).get();
    }

    private ConfigMap configMap(final String widgetName) {
        return client.configMaps().inNamespace(NS).withName(widgetName + "-config").get();
    }

    private static final class WidgetReconciler implements Reconciler<Widget> {
        private final Map<String, Integer> reconciles = new ConcurrentHashMap<>();
        private final Map<String, Integer> cleanups = new ConcurrentHashMap<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public ReconcileResult reconcile(final ReconcileContext<Widget> context) {
            final Widget widget = context.primary();
            reconciles.merge(widget.getMetadata().getName(), 1, Integer::sum);
            if (widget.getSpec().getSize() < 0) {
                throw new InvalidResourceException("size must not be negative");
            }
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return ReconcileResult.error(new IllegalStateException("boom"));
            }
            context.apply(new ConfigMapBuilder()
                    .withMetadata(context.newMetadata(widget.getMetadata().getName() + "-config"))
                    .withData(Map.of("size", String.valueOf(widget.getSpec().getSize())))
                    .build());
            context.patchStatus(latest -> {
                if (latest.getStatus() == null) {
                    latest.setStatus(new StandardStatus());
                }
                return latest.getStatus().markReady(latest.getMetadata().getGeneration());
            });
            return ReconcileResult.success();
        }

        @Override
        public ReconcileResult cleanup(final ReconcileContext<Widget> context) {
            cleanups.merge(context.primary().getMetadata().getName(), 1, Integer::sum);
            context.delete(ConfigMap.class, context.namespace(), context.primary().getMetadata().getName() + "-config");
            return ReconcileResult.success();
        }
    }

    private static final class RecordingObserver implements ControllerObserver {
        private final List<Cause> causes = new CopyOnWriteArrayList<>();
        private final List<Outcome> outcomes = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> finished = new ConcurrentHashMap<>();
        private final AtomicInteger finalizersAdded = new AtomicInteger();
        private final AtomicInteger retries = new AtomicInteger();

        @Override
        public void onEnqueued(final String controller,
                               final ReconcileRequest request,
                               final Cause cause) {
            causes.add(cause);
        }

        @Override
        public void onReconcileFinished(final String controller,
                                        final ReconcileRequest request,
                                        final Outcome outcome,
                                        final long durationNanos) {
            outcomes.add(outcome);
            finished.merge(request.name(), 1, Integer::sum);
        }

        @Override
        public void onFinalizerAdded(final String controller,
                                     final ReconcileRequest request) {
            finalizersAdded.incrementAndGet();
        }

        @Override
        public void onRetryScheduled(final String controller,
                                     final ReconcileRequest request,
                                     final int attempt,
                                     final Duration delay) {
            retries.incrementAndGet();
        }
    }

    // queues a request for a resource every tick, from start to close
    private static final class Ticker implements EventSource {
        private final ReconcileRequest request;
        private ScheduledExecutorService scheduler;

        private Ticker(final String namespace,
                       final String name) {
            this.request = new ReconcileRequest(namespace, name);
        }

        @Override
        public void start(final Enqueuer enqueuer) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(() -> enqueuer.enqueue(request), 0, 100, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }
}
