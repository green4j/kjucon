/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.github.green4j.kjucon.ReconcileRequest;
import io.github.green4j.kjucon.compose.Component;
import io.github.green4j.kjucon.compose.CompositionObserver;
import io.github.green4j.kjucon.compose.Readiness;

import java.util.List;

/**
 * Prints how compositions progress.
 */
public class StdOutCompositionObserver implements CompositionObserver {

    @Override
    public void onComponentWaiting(final ReconcileRequest primary,
                                   final Component<?, ?> component,
                                   final List<Component<?, ?>> blockedBy) {
        System.out.printf("  %s: %s waits for %s%n", primary, component, blockedBy);
    }

    @Override
    public void onComponentReadinessChanged(final ReconcileRequest primary,
                                            final Component<?, ?> component,
                                            final Readiness from,
                                            final Readiness to) {
        System.out.printf("  %s: %s -> %s%n", primary, component, to);
    }

    @Override
    public void onPruned(final ReconcileRequest primary,
                         final HasMetadata object) {
        System.out.printf("  %s: pruned %s/%s%n", primary, object.getKind(), object.getMetadata().getName());
    }

    @Override
    public void onDeleted(final ReconcileRequest primary,
                          final HasMetadata object) {
        System.out.printf("  %s: deleted %s/%s%n", primary, object.getKind(), object.getMetadata().getName());
    }

    @Override
    public void onCompositionReadinessChanged(final ReconcileRequest primary,
                                              final Readiness from,
                                              final Readiness to) {
        System.out.printf("%s is %s%n", primary, to);
    }
}
