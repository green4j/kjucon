/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.github.green4j.kjucon.test.Fixtures;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.green4j.kjucon.test.Fixtures.CONTROLLER;
import static io.github.green4j.kjucon.test.Fixtures.FORMER_CONTROLLER;
import static io.github.green4j.kjucon.test.Fixtures.OTHER_CONTROLLER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whose an object is, by its labels.
 */
class OwnershipTest {
    private static final String DOMAIN = Fixtures.LABEL_DOMAIN;
    private static final String UID = "uid-1";

    private final ReconcileContext<Widget> context = context();

    static Stream<Arguments> objects() {
        return Stream.of(
                // owner uid, controller label, managed-by label: ownership
                Arguments.of(null, null, null, Ownership.UNOWNED),
                Arguments.of("uid-2", CONTROLLER, CONTROLLER, Ownership.OTHER_RESOURCE),
                // the controller label decides
                Arguments.of(UID, CONTROLLER, "Helm", Ownership.OURS),
                Arguments.of(UID, FORMER_CONTROLLER, null, Ownership.OURS),
                Arguments.of(UID, OTHER_CONTROLLER, CONTROLLER, Ownership.OTHER_CONTROLLER),
                Arguments.of(UID, "unknown", null, Ownership.OTHER_CONTROLLER),
                // without it, managed-by - for the names the operator knows only
                Arguments.of(UID, null, CONTROLLER, Ownership.OURS),
                Arguments.of(UID, null, FORMER_CONTROLLER, Ownership.OURS),
                Arguments.of(UID, null, OTHER_CONTROLLER, Ownership.OTHER_CONTROLLER),
                Arguments.of(UID, null, "Helm", Ownership.UNOWNED),
                Arguments.of(UID, null, null, Ownership.UNOWNED));
    }

    @ParameterizedTest(name = "owner {0}, controller {1}, managed by {2}: {3}")
    @MethodSource("objects")
    void ownershipByTheLabels(final String ownerUid,
                              final String controller,
                              final String managedBy,
                              final Ownership expected) {
        assertEquals(expected, context.ownershipOf(object(ownerUid, controller, managedBy)));
    }

    private static ConfigMap object(final String ownerUid,
                                    final String controller,
                                    final String managedBy) {
        return new ConfigMapBuilder().withNewMetadata().withName("c")
                .withLabels(Fixtures.ownerLabels(ownerUid, controller, managedBy)).endMetadata().build();
    }

    private static ReconcileContext<Widget> context() {
        final Widget primary = Widget.of(Fixtures.NAMESPACE, "w1", 1);
        primary.getMetadata().setUid(UID);
        return new ReconcileContext<>(null, null, null, DOMAIN, CONTROLLER, List.of(FORMER_CONTROLLER),
                Set.of(OTHER_CONTROLLER), true, ReconcileRequest.of(primary), Widget.class, primary, 1);
    }
}
