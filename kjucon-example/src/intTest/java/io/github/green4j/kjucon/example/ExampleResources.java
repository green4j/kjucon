/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example;

import io.github.green4j.kjucon.example.webapp.WebApp;
import io.github.green4j.kjucon.example.webapp.WebAppSpec;

/**
 * Resources of the examples, as the integration tests create them.
 */
public final class ExampleResources {

    private ExampleResources() {
    }

    /**
     * @param namespace of the application
     * @param name of the application
     * @return a new WebApp with the default spec
     */
    public static WebApp webApp(final String namespace,
                                final String name) {
        final WebApp app = new WebApp();
        app.getMetadata().setName(name);
        app.getMetadata().setNamespace(namespace);
        app.setSpec(new WebAppSpec());
        return app;
    }
}
