/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.greeting;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.github.green4j.kjucon.ControllerBuilder;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.ReconcileResult;
import io.github.green4j.kjucon.Reconciler;
import io.github.green4j.kjucon.status.StandardStatus;

import java.util.function.Predicate;

/**
 * Keeps a ConfigMap with the text of each {@link Greeting} and reports in its status - the plainest controller
 * there is, on kjucon-core alone.
 * <p>
 * The ConfigMap is created with the metadata the context describes: an owner reference, so the garbage collector
 * deletes it with the greeting and its changes wake the controller - which owns ConfigMaps - and kjucon's labels.
 * So there is nothing to clean up, and the controller does without a finalizer.
 */
public final class GreetingReconciler implements Reconciler<Greeting> {
    /** The name of the controller. */
    public static final String NAME = "greetings";
    /** The key of the text in the ConfigMap. */
    public static final String TEXT = "text";

    /**
     * Names the controller, has it own the ConfigMaps - a change of one wakes its greeting - and does without a
     * finalizer: the garbage collector deletes a greeting's ConfigMap with it.
     *
     * @param controller the builder of the controller
     */
    @Override
    public void configure(final ControllerBuilder<Greeting> controller) {
        controller.name(NAME)
                .owns(ConfigMap.class)
                .finalizer(false);
    }

    /**
     * @param greeting a greeting
     * @return the name of its ConfigMap
     */
    public static String configMapName(final Greeting greeting) {
        return greeting.getMetadata().getName() + "-greeting";
    }

    @Override
    public ReconcileResult reconcile(final ReconcileContext<Greeting> context) {
        final Greeting greeting = context.primary();
        final String to = greeting.getSpec() == null ? null : greeting.getSpec().getTo();
        if (to == null || to.isBlank()) {
            // nothing to retry until somebody fixes the spec: say so, and wait for the change
            setStatus(context, status -> status.markError("spec.to is required", generation(context)));
            return ReconcileResult.success();
        }
        context.apply(new ConfigMapBuilder()
                .withMetadata(context.newMetadata(configMapName(greeting)))
                .addToData(TEXT, "Hello, " + to + '!')
                .build());
        setStatus(context, status -> status.markReady(generation(context)));
        return ReconcileResult.success();
    }

    private static Long generation(final ReconcileContext<Greeting> context) {
        return context.primary().getMetadata().getGeneration();
    }

    // the status is written only if the update changes it
    private static void setStatus(final ReconcileContext<Greeting> context,
                                  final Predicate<StandardStatus> update) {
        context.patchStatus(greeting -> {
            if (greeting.getStatus() == null) {
                greeting.setStatus(new StandardStatus());
            }
            return update.test(greeting.getStatus());
        });
    }
}
