/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.github.green4j.kjucon.status.StandardStatus;

/**
 * A custom resource for tests.
 */
@Group("test.kjucon.green4j.github.io")
@Version("v1")
public class Widget extends CustomResource<WidgetSpec, StandardStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;

    /**
     * @param namespace of the widget
     * @param name of the widget
     * @param size of the widget
     * @return a new widget
     */
    public static Widget of(final String namespace,
                            final String name,
                            final int size) {
        final Widget widget = new Widget();
        widget.getMetadata().setNamespace(namespace);
        widget.getMetadata().setName(name);
        final WidgetSpec spec = new WidgetSpec();
        spec.setSize(size);
        widget.setSpec(spec);
        return widget;
    }
}
