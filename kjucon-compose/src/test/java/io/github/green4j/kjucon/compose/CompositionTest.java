/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.github.green4j.kjucon.test.Widget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionTest {

    @Test
    void ordersByDependenciesThenInsertion() {
        final Composition.Builder<Widget> builder = Composition.builder();
        final Component<Widget, Service> service = builder.add(component("web", Service.class));
        final Component<Widget, Deployment> deployment = builder.add(component("web", Deployment.class));
        final Component<Widget, ConfigMap> config = builder.add(component("config", ConfigMap.class));
        final Component<Widget, ConfigMap> other = builder.add(component("other", ConfigMap.class));
        deployment.rolloutOn(config); // a dependency too
        service.dependsOn(deployment);

        final Composition<Widget> composition = builder.build();

        assertEquals(List.of(config, deployment, service, other), composition.components());
        assertEquals(List.of(deployment), composition.dependentsOf(config));
        assertTrue(composition.dependentsOf(other).isEmpty());
    }

    static Stream<Arguments> invalidCompositions() {
        return Stream.of(
                Arguments.of("a cycle", (Consumer<Composition.Builder<Widget>>) b -> {
                    final Component<Widget, ConfigMap> x = b.add(component("x", ConfigMap.class));
                    b.add(component("y", ConfigMap.class)).dependsOn(x);
                    x.dependsOn(b.build().components().get(1));
                }),
                Arguments.of("a dependency outside the composition", (Consumer<Composition.Builder<Widget>>) b ->
                        b.add(component("x", ConfigMap.class)).dependsOn(component("y", ConfigMap.class))),
                Arguments.of("two components for one object", (Consumer<Composition.Builder<Widget>>) b -> {
                    b.add(component("x", ConfigMap.class));
                    b.add(component("x", ConfigMap.class));
                }),
                Arguments.of("a component depending on itself", (Consumer<Composition.Builder<Widget>>) b -> {
                    final Component<Widget, ConfigMap> x = component("x", ConfigMap.class);
                    x.dependsOn(x);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCompositions")
    void invalidCompositionIsRejected(final String what,
                                      final Consumer<Composition.Builder<Widget>> composition) {
        final Composition.Builder<Widget> builder = Composition.builder();
        assertThrows(IllegalArgumentException.class, () -> {
            composition.accept(builder);
            builder.build();
        });
    }

    static Stream<Arguments> distinctObjects() {
        return Stream.of(
                Arguments.of("the same name in another namespace",
                        component("x", ConfigMap.class), component("x", ConfigMap.class).namespace("elsewhere")),
                Arguments.of("the same kind and name in another group", component("x", Ingress.class),
                        component("x", io.fabric8.kubernetes.api.model.extensions.Ingress.class)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("distinctObjects")
    void distinctObjectsAreDistinctComponents(final String what,
                                              final Component<Widget, ?> one,
                                              final Component<Widget, ?> other) {
        final Composition.Builder<Widget> builder = Composition.builder();
        builder.add(one);
        builder.add(other);
        assertEquals(2, builder.build().components().size());
    }

    private static <R extends HasMetadata> Component<Widget, R> component(final String name,
                                                                          final Class<R> type) {
        return Component.of(name, type, (context, metadata) -> {
            throw new UnsupportedOperationException();
        });
    }
}
