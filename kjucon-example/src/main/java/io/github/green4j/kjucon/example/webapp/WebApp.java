/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.webapp;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.github.green4j.kjucon.example.Examples;
import io.github.green4j.kjucon.status.StandardStatus;

/**
 * A web application: composed of a ConfigMap with its page, a Deployment serving it and a Service in front.
 */
@Group(Examples.GROUP)
@Version("v1")
public class WebApp extends CustomResource<WebAppSpec, StandardStatus> implements Namespaced {

    private static final long serialVersionUID = 1L;
}
