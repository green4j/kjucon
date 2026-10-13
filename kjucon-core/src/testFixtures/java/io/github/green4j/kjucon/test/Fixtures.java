/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.fabric8.kubernetes.client.CustomResource;
import io.github.green4j.kjucon.Labels;
import io.github.green4j.kjucon.status.StandardStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * What the unit tests share: the namespace of the mock API server's client, the label domain, and the names of the
 * controllers they run.
 */
public final class Fixtures {
    /** The namespace of {@link MockKubernetes#client()}. */
    public static final String NAMESPACE = "test";
    /** The label domain of the operators under test. */
    public static final String LABEL_DOMAIN = "kjucon.example.com";
    /** The controller of the widgets. */
    public static final String CONTROLLER = "widgets";
    /** The name the controller of the widgets had before. */
    public static final String FORMER_CONTROLLER = "widgets-old";
    /** Another controller of the widgets. */
    public static final String OTHER_CONTROLLER = "gadgets";

    private Fixtures() {
    }

    /**
     * The labels kjucon tells the owner and the controller of an object by.
     *
     * @param ownerUid of the resource owning the object, null for none
     * @param controller of the object, null for none
     * @param managedBy the value of {@code app.kubernetes.io/managed-by}, null for none
     * @return a new mutable map
     */
    public static Map<String, String> ownerLabels(final String ownerUid,
                                                  final String controller,
                                                  final String managedBy) {
        final Map<String, String> labels = new HashMap<>();
        if (ownerUid != null) {
            labels.put(Labels.ownerUid(LABEL_DOMAIN), ownerUid);
        }
        if (controller != null) {
            labels.put(Labels.controller(LABEL_DOMAIN), controller);
        }
        if (managedBy != null) {
            labels.put(Labels.MANAGED_BY, managedBy);
        }
        return labels;
    }

    /**
     * @param resource with a standard status, may be null
     * @param phase expected
     * @return true if the resource is there and its status is in the phase
     */
    public static boolean hasPhase(final CustomResource<?, StandardStatus> resource,
                                   final String phase) {
        return resource != null && resource.getStatus() != null && resource.getStatus().hasPhase(phase);
    }
}
