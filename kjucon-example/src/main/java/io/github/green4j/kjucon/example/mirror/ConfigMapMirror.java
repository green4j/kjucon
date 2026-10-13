/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.mirror;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.green4j.kjucon.ControllerBuilder;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.ReconcileResult;
import io.github.green4j.kjucon.Reconciler;
import io.github.green4j.kjucon.example.Examples;
import io.github.green4j.kjucon.util.Names;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * A controller with no custom resource of its own: it copies every ConfigMap labelled {@code mirror=true} into a
 * namespace of a target cluster, keeps the copy up to date, and deletes it when the original is deleted.
 * <p>
 * The primary resources are ConfigMaps somebody else owns; the writes go to a client of the target, which may be
 * another cluster altogether - or this one, as in the integration test. kjucon brings what any controller needs:
 * the shared informer, the work queue, back-off, the finalizer which holds a deleted original until its copy is
 * gone, leader election and the observers.
 * {@link MirrorController} runs it.
 */
public final class ConfigMapMirror implements Reconciler<ConfigMap> {
    /** The name of the controller: its finalizer and the field manager of the copies. */
    public static final String NAME = "configmap-mirror";
    /** The label which marks a ConfigMap to mirror, with the value {@link #ENABLED}. */
    public static final String LABEL = "mirror";
    /** The value of {@link #LABEL} on a ConfigMap to mirror. */
    public static final String ENABLED = "true";
    /** The annotation on a copy naming its original, namespace/name. */
    public static final String SOURCE = Examples.DOMAIN + "/mirrored-from";

    private static final Duration RESYNC = Duration.ofMinutes(10);

    private final KubernetesClient target;
    private final String targetNamespace;

    /**
     * @param target client of the cluster the copies go to
     * @param targetNamespace of the copies
     */
    public ConfigMapMirror(final KubernetesClient target,
                           final String targetNamespace) {
        this.target = Objects.requireNonNull(target, "target");
        this.targetNamespace = Objects.requireNonNull(targetNamespace, "targetNamespace");
    }

    /**
     * Names the controller and has it reconcile the labelled ConfigMaps only, looking again now and then: nothing
     * tells the controller when a copy is changed or deleted in the target. A target the operator can watch would
     * rather be an EventSource.
     *
     * @param controller the builder of the controller
     */
    @Override
    public void configure(final ControllerBuilder<ConfigMap> controller) {
        controller.name(NAME)
                .selector(Map.of(LABEL, ENABLED))
                .resyncPeriod(RESYNC);
    }

    @Override
    public ReconcileResult reconcile(final ReconcileContext<ConfigMap> context) {
        final ConfigMap original = context.primary();
        final ConfigMap copy = new ConfigMapBuilder()
                .withNewMetadata()
                .withNamespace(targetNamespace)
                .withName(copyName(original))
                .addToAnnotations(SOURCE, context.request().key())
                .endMetadata()
                .withData(original.getData())
                .withBinaryData(original.getBinaryData())
                .build();
        // applied, not replaced: a key removed from the original goes from the copy, what others add to it stays
        target.resource(copy).fieldManager(NAME).forceConflicts().serverSideApply();
        return ReconcileResult.success();
    }

    @Override
    public ReconcileResult cleanup(final ReconcileContext<ConfigMap> context) {
        // only now the finalizer goes and the original with it: the copy cannot be forgotten
        target.configMaps().inNamespace(targetNamespace).withName(copyName(context.primary())).delete();
        return ReconcileResult.success();
    }

    /**
     * @param original ConfigMap
     * @return the name of its copy - unique across the namespaces of the originals
     */
    public static String copyName(final ConfigMap original) {
        return Names.child(original.getMetadata().getNamespace(), original.getMetadata().getName());
    }
}
