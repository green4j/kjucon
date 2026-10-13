/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.example.greeting;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.github.green4j.kjucon.example.Examples;
import io.github.green4j.kjucon.status.StandardStatus;

/**
 * A greeting: kept as a ConfigMap with its text by {@link GreetingReconciler} - a single custom resource and a
 * plain reconciler, with no composition.
 */
@Group(Examples.GROUP)
@Version("v1")
public class Greeting extends CustomResource<GreetingSpec, StandardStatus> implements Namespaced {
    private static final long serialVersionUID = 1L;
}
