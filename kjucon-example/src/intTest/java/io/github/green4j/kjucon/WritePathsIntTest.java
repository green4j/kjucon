/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.green4j.kjucon.compose.Composition;
import io.github.green4j.kjucon.compose.CompositionReconciler;
import io.github.green4j.kjucon.compose.DeletePolicy;
import io.github.green4j.kjucon.compose.Readiness;
import io.github.green4j.kjucon.example.ExampleResources;
import io.github.green4j.kjucon.example.K3sCluster;
import io.github.green4j.kjucon.example.webapp.WebApp;
import io.github.green4j.kjucon.status.Conditions;
import io.github.green4j.kjucon.status.StandardStatus;
import io.github.green4j.kjucon.test.Await;
import io.github.green4j.kjucon.test.Conflicts;
import io.github.green4j.kjucon.test.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.github.green4j.kjucon.example.K3sCluster.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each way kjucon writes, against a real API server - the cases where server-side apply alone is not enough, or
 * does something a merge would not:
 * <ul>
 *   <li>apply (SSA): a field no longer applied is removed, a field of another manager is kept, a field of ours
 *   changed by someone else is taken back;</li>
 *   <li>status (SSA of the status alone on the status subresource): a message is cleared, conditions follow and
 *   fit metav1.Condition, nothing but the status is owned by the writer;</li>
 *   <li>finalizers (JSON patch: test of the list, then replace): a finalizer of somebody else survives both the
 *   addition and the removal of ours, a write of anything else does not fail the patch, a change of the list
 *   does;</li>
 *   <li>release of an orphan (JSON patch): owner reference and labels go, the object outlives its owner;</li>
 *   <li>an object of the same name already there: adopted if nobody controls it, refused if somebody does.</li>
 * </ul>
 */
class WritePathsIntTest {
    private static final String NS = "writes";
    private static final String DOMAIN = "writes.kjucon.green4j.github.io";
    private static final String CONTROLLER = "writes";
    private static final String FOREIGN_FINALIZER = "other.example.com/hold";

    // what the composition of each WebApp is, by its name - changed by the tests
    private static final Map<String, Desired> DESIRED = new ConcurrentHashMap<>();

    private static KubernetesClient client;
    private static Operator operator;

    @BeforeAll
    static void start() {
        client = K3sCluster.namespace(NS);
        final Operator.Builder builder = Operator.builder()
                .client(client)
                .watchNamespace(NS)
                .labelDomain(DOMAIN);
        builder.controller(WebApp.class,
                        CompositionReconciler.<WebApp>builder(context -> {
                            final String name = context.primary().getMetadata().getName();
                            final Desired desired = DESIRED.getOrDefault(name, new Desired());
                            final Composition.Builder<WebApp> composition = Composition.builder();
                            composition.component(name + "-cfg", ConfigMap.class,
                                            (ctx, meta) -> new ConfigMapBuilder()
                                                    .withMetadata(meta)
                                                    .editMetadata().addToLabels(desired.labels).endMetadata()
                                                    .withData(desired.data)
                                                    .build())
                                    .readyWhen(cm -> desired.hold ? Readiness.inProgress("held") : Readiness.ready());
                            if (desired.keep) {
                                composition.component(name + "-keep", ConfigMap.class,
                                                (ctx, meta) -> new ConfigMapBuilder().withMetadata(meta).build())
                                        .deletePolicy(DeletePolicy.ORPHAN);
                            }
                            return composition.build();
                        })
                                .manages(ConfigMap.class)
                                .cleanupPoll(Duration.ofMillis(200))
                                .build())
                .name(CONTROLLER);
        operator = builder.build();
        Await.started(operator, TIMEOUT);
    }

    @AfterAll
    static void stop() {
        if (operator != null) {
            operator.close();
        }
    }

    @Test
    void applyRemovesWhatIsNoLongerAppliedAndKeepsWhatOthersOwn() {
        final String apply = "apply";
        final Desired desired = desired(apply);
        desired.data = Map.of("a", "1", "b", "2");
        desired.labels = Map.of("tier", "front");
        create(apply);
        Await.until(() -> configMap(apply + "-cfg") != null && "2".equals(configMap(apply + "-cfg").getData().get("b")),
                TIMEOUT, "the first apply");

        // another manager adds a key and a label of its own
        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(apply + "-cfg").edit(cm -> {
            cm.getData().put("c", "theirs");
            cm.getMetadata().getLabels().put("their-label", "x");
            return cm;
        }));

        desired.data = Map.of("a", "1");
        desired.labels = Map.of();
        operator.controller(CONTROLLER).enqueue(NS, apply);

        Await.until(() -> !configMap(apply + "-cfg").getData().containsKey("b"), TIMEOUT, "b to be removed");
        final ConfigMap cm = configMap(apply + "-cfg");
        assertFalse(cm.getMetadata().getLabels().containsKey("tier"), "a label no longer applied is removed");
        assertEquals("theirs", cm.getData().get("c"), "a key of another manager is kept");
        assertEquals("x", cm.getMetadata().getLabels().get("their-label"), "a label of another manager is kept");

        // somebody changes a field we own: taken back on the event of the change
        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(apply + "-cfg").edit(c -> {
            c.getData().put("a", "tampered");
            return c;
        }));
        Await.until(() -> "1".equals(configMap(apply + "-cfg").getData().get("a")), TIMEOUT, "a to be taken back");
    }

    @Test
    void statusMessageAndConditionsFollowThePhase() {
        final String statusName = "status";
        desired(statusName).hold = true;
        create(statusName);
        Await.until(() -> hasPhase(statusName, StandardStatus.PENDING), TIMEOUT, "Pending");
        assertEquals("ConfigMap/status-cfg: held", webApp(statusName).getStatus().getMessage());
        assertTrue(Conditions.isTrue(webApp(statusName).getStatus().getConditions(), Conditions.RECONCILING));

        desired(statusName).hold = false;
        operator.controller(CONTROLLER).enqueue(NS, statusName);

        Await.until(() -> hasPhase(statusName, StandardStatus.READY), TIMEOUT, "Ready");
        final StandardStatus status = webApp(statusName).getStatus();
        assertNull(status.getMessage(), "a message no longer applied is removed from the status");
        assertTrue(Conditions.isTrue(status.getConditions(), Conditions.READY));
        assertFalse(Conditions.isTrue(status.getConditions(), Conditions.RECONCILING));
        assertFalse(Conditions.isTrue(status.getConditions(), Conditions.STALLED));
        assertEquals(webApp(statusName).getMetadata().getGeneration(), status.getObservedGeneration());
        for (final Condition condition : status.getConditions()) {
            assertNotNull(condition.getReason(), "metav1.Condition requires a reason");
            assertNotNull(condition.getMessage(), "metav1.Condition requires a message");
        }
    }

    @Test
    void statusWriteOwnsTheStatusOnly() {
        final String owner = "owner";
        create(owner);
        Await.until(() -> hasPhase(owner, StandardStatus.READY), TIMEOUT, "Ready");

        final List<ManagedFieldsEntry> statusEntries = webApp(owner).getMetadata().getManagedFields().stream()
                .filter(e -> CONTROLLER.equals(e.getManager()) && "status".equals(e.getSubresource()))
                .collect(Collectors.toList());
        assertEquals(1, statusEntries.size(), statusEntries.toString());
        assertEquals(Set.of("f:status"), statusEntries.get(0).getFieldsV1().getAdditionalProperties().keySet(),
                "the status writer owns nothing but the status");
    }

    @Test
    void finalizerOfSomebodyElseSurvivesOurs() {
        final String fin = "fin";
        final WebApp app = ExampleResources.webApp(NS, fin);
        app.getMetadata().setFinalizers(new ArrayList<>(List.of(FOREIGN_FINALIZER)));
        client.resource(app).create();

        final String ours = DOMAIN + '/' + CONTROLLER;
        Await.until(() -> webApp(fin).getMetadata().getFinalizers().contains(ours), TIMEOUT, "our finalizer");
        assertTrue(webApp(fin).getMetadata().getFinalizers().contains(FOREIGN_FINALIZER), "added beside theirs");

        client.resources(WebApp.class).inNamespace(NS).withName(fin).delete();

        Await.until(() -> !webApp(fin).getMetadata().getFinalizers().contains(ours), TIMEOUT, "ours removed");
        assertEquals(List.of(FOREIGN_FINALIZER), webApp(fin).getMetadata().getFinalizers(),
                "theirs is still holding the resource");
        assertNull(configMap(fin + "-cfg"), "cleaned up");

        Conflicts.retry(() -> client.resources(WebApp.class).inNamespace(NS).withName(fin).edit(w -> {
            w.getMetadata().getFinalizers().remove(FOREIGN_FINALIZER);
            return w;
        }));
        Await.until(() -> webApp(fin) == null, TIMEOUT, "the resource to be gone");
    }

    @Test
    void finalizerPatchIgnoresOtherWritesAndCatchesAChangeOfTheList() {
        final String finList = "fin-list";
        final String ours = "mine.example.com/hold";
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(finList).addToFinalizers(FOREIGN_FINALIZER, ours).endMetadata()
                .build()).create();

        // somebody writes something else after our read: the patch still holds, theirs is kept
        final ConfigMap staleForOtherWrite = configMap(finList);
        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(finList).edit(cm -> {
            cm.getMetadata().getAnnotations().put("touched", "yes");
            return cm;
        }));
        final ConfigMap patched = Finalizers.remove(client, staleForOtherWrite, ours);
        assertEquals(List.of(FOREIGN_FINALIZER), patched.getMetadata().getFinalizers());
        assertEquals("yes", patched.getMetadata().getAnnotations().get("touched"), "the other write is kept");

        // somebody changes the list after our read: the patch fails, as a concurrent change, and loses nothing
        final ConfigMap staleForList = configMap(finList);
        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(finList).edit(cm -> {
            cm.getMetadata().getFinalizers().add("third.example.com/hold");
            return cm;
        }));
        final RuntimeException error = assertThrows(RuntimeException.class,
                () -> Finalizers.add(client, staleForList, ours));
        assertTrue(Finalizers.isConcurrentChange(error), error.toString());
        assertEquals(List.of(FOREIGN_FINALIZER, "third.example.com/hold"),
                configMap(finList).getMetadata().getFinalizers());

        Conflicts.retry(() -> client.configMaps().inNamespace(NS).withName(finList).edit(cm -> {
            cm.getMetadata().setFinalizers(new ArrayList<>());
            return cm;
        }));
        client.configMaps().inNamespace(NS).withName(finList).delete();
    }

    @Test
    void orphanIsReleasedAndOutlivesItsOwner() {
        final String orphan = "orphan";
        desired(orphan).keep = true;
        create(orphan);
        Await.until(() -> configMap(orphan + "-keep") != null, TIMEOUT, "the orphan-to-be");
        assertFalse(configMap(orphan + "-keep").getMetadata().getOwnerReferences().isEmpty());

        desired(orphan).keep = false;
        operator.controller(CONTROLLER).enqueue(NS, orphan);

        Await.until(() -> configMap(orphan + "-keep").getMetadata().getOwnerReferences().isEmpty(), TIMEOUT,
                "the release");
        final ConfigMap released = configMap(orphan + "-keep");
        assertFalse(released.getMetadata().getLabels().containsKey(Labels.ownerUid(DOMAIN)));
        assertFalse(released.getMetadata().getLabels().containsKey(Labels.MANAGED_BY));

        client.resources(WebApp.class).inNamespace(NS).withName(orphan).delete();
        Await.until(() -> webApp(orphan) == null && configMap(orphan + "-cfg") == null, TIMEOUT, "the owner gone");
        assertNotNull(configMap(orphan + "-keep"), "released objects are neither pruned nor collected");
    }

    @Test
    void anObjectNobodyControlsIsAdopted() {
        final String adopt = "adopt";
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(adopt + "-cfg").endMetadata()
                .withData(Map.of("a", "old", "z", "theirs"))
                .build()).create();
        desired(adopt).data = Map.of("a", "new");
        create(adopt);

        Await.until(() -> "new".equals(configMap(adopt + "-cfg").getData().get("a")), TIMEOUT, "the adoption");
        final ConfigMap cm = configMap(adopt + "-cfg");
        assertEquals(adopt, cm.getMetadata().getOwnerReferences().get(0).getName());
        assertEquals("theirs", cm.getData().get("z"), "what the creator set and we do not apply stays");
        Await.until(() -> hasPhase(adopt, StandardStatus.READY), TIMEOUT, "Ready");
    }

    @Test
    void applyCarryingAStaleResourceVersionIsRefused() {
        final String precondition = "precondition";
        final ConfigMap created = client.resource(new ConfigMapBuilder().withNewMetadata().withNamespace(NS)
                .withName(precondition).endMetadata().addToData("a", "1").build()).create();
        final String seen = created.getMetadata().getResourceVersion();
        client.configMaps().inNamespace(NS).withName(precondition).edit(c -> {
            c.getData().put("b", "2");
            return c;
        });

        final ConfigMap adopting = new ConfigMapBuilder().withNewMetadata().withNamespace(NS).withName(precondition)
                .withResourceVersion(seen).endMetadata().addToData("a", "3").build();
        final KubernetesClientException error = assertThrows(KubernetesClientException.class,
                () -> ServerSideApply.apply(client, CONTROLLER, adopting));
        assertEquals(409, error.getCode(), "what an adoption over a changed object gets");
        assertEquals("1", configMap(precondition).getData().get("a"));
    }

    @Test
    void anObjectSomebodyElseControlsIsNotTakenOver() {
        final String taken = "taken";
        final String foreignOwner = "foreign-owner";
        final ConfigMap owner = client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(foreignOwner).endMetadata().build()).create();
        client.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                .withNewMetadata().withName(taken + "-cfg")
                .addToOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("v1").withKind("ConfigMap").withName(foreignOwner)
                        .withUid(owner.getMetadata().getUid()).withController(true).build())
                .endMetadata()
                .withData(Map.of("a", "theirs"))
                .build()).create();
        desired(taken).data = Map.of("a", "ours");
        create(taken);

        Await.until(() -> hasPhase(taken, StandardStatus.ERROR), TIMEOUT, "the Error status");
        final ConfigMap cm = configMap(taken + "-cfg");
        assertEquals("theirs", cm.getData().get("a"), "untouched");
        assertEquals(1, cm.getMetadata().getOwnerReferences().size());
        assertEquals(foreignOwner, cm.getMetadata().getOwnerReferences().get(0).getName());
        assertTrue(webApp(taken).getStatus().getMessage().contains("Only one reference can have Controller set"),
                webApp(taken).getStatus().getMessage());
        assertTrue(webApp(taken).getStatus().getMessage().length() <= 1024);
    }

    private static Desired desired(final String name) {
        return DESIRED.computeIfAbsent(name, k -> new Desired());
    }

    private static void create(final String name) {
        client.resource(ExampleResources.webApp(NS, name)).create();
    }


    private static boolean hasPhase(final String name,
                                    final String phase) {
        return Fixtures.hasPhase(webApp(name), phase);
    }

    private static WebApp webApp(final String name) {
        return client.resources(WebApp.class).inNamespace(NS).withName(name).get();
    }

    private static ConfigMap configMap(final String name) {
        return client.configMaps().inNamespace(NS).withName(name).get();
    }

    private static final class Desired {
        private volatile Map<String, String> data = Map.of("a", "1");
        private volatile Map<String, String> labels = Map.of();
        private volatile boolean hold;
        private volatile boolean keep;
    }
}
