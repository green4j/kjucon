/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which updates wake a controller by default.
 */
class EventFilterTest {
    private final EventFilter<ConfigMap> filter = EventFilter.defaults();

    @ParameterizedTest(name = "generation {0} -> {1}, version {2} -> {3}: {4}")
    @CsvSource(value = {
        "1, 2, 1, 2, true",       // the spec changed
        "1, 1, 1, 2, false",      // the status alone changed
        "null, null, 1, 2, true", // a type without a generation: a ConfigMap's data, say
        "null, null, 1, 1, false" // a resync of one
    }, nullValues = "null")
    void defaults(final Long oldGeneration,
                  final Long newGeneration,
                  final String oldVersion,
                  final String newVersion,
                  final boolean accepted) {
        assertEquals(accepted, filter.accept(object(oldGeneration, oldVersion), object(newGeneration, newVersion)));
    }

    private static ConfigMap object(final Long generation,
                                    final String resourceVersion) {
        return new ConfigMapBuilder().withNewMetadata().withName("c").withGeneration(generation)
                .withResourceVersion(resourceVersion).endMetadata().build();
    }
}
