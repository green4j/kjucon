/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

import io.github.green4j.kjucon.InvalidResourceException;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.compose.Composition;
import io.github.green4j.kjucon.compose.CompositionFactory;
import io.github.green4j.kjucon.util.Names;

import java.util.HashSet;
import java.util.Set;

/**
 * A {@link WebAppSet} is one {@link WebApp} per application - a level of the hierarchy which another controller
 * composes further. An application removed from the set is pruned, and with it everything its WebApp owns.
 */
public class WebAppSetComposition implements CompositionFactory<WebAppSet> {

    @Override
    public Composition<WebAppSet> create(final ReconcileContext<WebAppSet> context) {
        final WebAppSet set = context.primary();
        final Composition.Builder<WebAppSet> builder = Composition.<WebAppSet>builder()
                .propagateLabels("team");
        final Set<String> names = new HashSet<>();
        for (final WebAppSetSpec.App app : set.getSpec().getApps()) {
            if (app.getName() == null || !names.add(app.getName())) {
                throw new InvalidResourceException("every app needs a name of its own: " + app.getName());
            }
            final String name = Names.child(set.getMetadata().getName(), app.getName());
            builder.component(name, WebApp.class, (ctx, meta) -> {
                final WebApp webApp = new WebApp();
                webApp.setMetadata(meta);
                final WebAppSpec spec = new WebAppSpec();
                spec.setImage(app.getImage());
                spec.setReplicas(app.getReplicas());
                spec.setMessage(app.getMessage());
                webApp.setSpec(spec);
                return webApp;
            });
        }
        return builder.build();
    }
}
