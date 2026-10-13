/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.github.green4j.kjucon.InvalidResourceException;
import io.github.green4j.kjucon.ReconcileContext;
import io.github.green4j.kjucon.compose.Component;
import io.github.green4j.kjucon.compose.Composition;
import io.github.green4j.kjucon.compose.CompositionFactory;

import java.util.Map;

/**
 * A {@link WebApp} is a ConfigMap holding its page, a Deployment serving the page - rolled whenever the page
 * changes - and a Service, applied once the Deployment is available.
 */
public class WebAppComposition implements CompositionFactory<WebApp> {
    private static final String APP_LABEL = "app";
    private static final int PORT = 80;
    private static final String PAGE_VOLUME = "page";
    private static final String NGINX_ROOT = "/usr/share/nginx/html";

    @Override
    public Composition<WebApp> create(final ReconcileContext<WebApp> context) {
        final WebApp app = context.primary();
        final String name = app.getMetadata().getName();
        final WebAppSpec spec = app.getSpec();
        if (spec == null || spec.getReplicas() < 0) {
            throw new InvalidResourceException("spec.replicas must not be negative");
        }

        final Composition.Builder<WebApp> builder = Composition.builder();
        final Component<WebApp, ConfigMap> page = builder.component(name + "-page", ConfigMap.class,
                (ctx, meta) -> page(meta, spec));
        final Component<WebApp, Deployment> server = builder.component(name, Deployment.class,
                        (ctx, meta) -> server(meta, name, page.name(), spec))
                .rolloutOn(page);
        builder.component(name, Service.class, (ctx, meta) -> service(meta, name))
                .dependsOn(server);
        return builder.build();
    }

    private static ConfigMap page(final ObjectMeta meta,
                                  final WebAppSpec spec) {
        return new ConfigMapBuilder()
                .withMetadata(meta)
                .withData(Map.of("index.html", "<html><body><h1>" + spec.getMessage() + "</h1></body></html>"))
                .build();
    }

    private static Deployment server(final ObjectMeta meta,
                                     final String name,
                                     final String pageName,
                                     final WebAppSpec spec) {
        return new DeploymentBuilder()
                .withMetadata(meta)
                .withNewSpec()
                .withReplicas(spec.getReplicas())
                .withNewSelector().addToMatchLabels(APP_LABEL, name).endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels(APP_LABEL, name).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("web")
                .withImage(spec.getImage())
                .addNewPort().withContainerPort(PORT).endPort()
                .addNewVolumeMount().withName(PAGE_VOLUME).withMountPath(NGINX_ROOT).endVolumeMount()
                .endContainer()
                .addNewVolume().withName(PAGE_VOLUME).withNewConfigMap().withName(pageName).endConfigMap().endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Service service(final ObjectMeta meta,
                                   final String name) {
        return new ServiceBuilder()
                .withMetadata(meta)
                .withNewSpec()
                .addToSelector(APP_LABEL, name)
                .addNewPort().withName("http").withPort(PORT).withNewTargetPort(PORT).endPort()
                .endSpec()
                .build();
    }
}
