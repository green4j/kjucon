/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.github.green4j.kjucon.test.Fixtures;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecondaryMapperTest {
    private static final String DOMAIN = Fixtures.LABEL_DOMAIN;

    @Test
    void mapsByOwnerReferenceOfTheOwnerKindOnly() {
        final ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("cm")
                .addToOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("test.kjucon.green4j.github.io/v1").withKind("Widget")
                        .withName("w1").withUid("u1").build())
                .addToOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("apps/v1").withKind("Widget")
                        .withName("other-group").withUid("u2").build())
                .endMetadata()
                .build();
        assertEquals(List.of(new ReconcileRequest("ns", "w1")),
                List.copyOf(SecondaryMapper.<ConfigMap>byOwnerReference(Widget.class).map(cm)));
    }

    @ParameterizedTest
    @CsvSource({
        "Widget.test.kjucon.green4j.github.io, home/w1, true", // across namespaces
        "Gadget, ns/g, false"                                  // of another kind
    })
    void mapsByOwnerAnnotation(final String ownerKind,
                               final String owner,
                               final boolean mapped) {
        final ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("elsewhere").withName("cm")
                .withAnnotations(Map.of(Labels.ownerKind(DOMAIN), ownerKind, Labels.owner(DOMAIN), owner))
                .endMetadata()
                .build();
        final String[] namespaceAndName = owner.split("/");
        assertEquals(mapped ? List.of(new ReconcileRequest(namespaceAndName[0], namespaceAndName[1])) : List.of(),
                List.copyOf(SecondaryMapper.<ConfigMap>byOwner(DOMAIN, Widget.class).map(cm)));
    }
}
