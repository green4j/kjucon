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
 * A set of web applications: composed of one {@link WebApp} per application - which another controller composes
 * in turn. The set is ready when every application is.
 */
@Group(Examples.GROUP)
@Version("v1")
public class WebAppSet extends CustomResource<WebAppSetSpec, StandardStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;
}
