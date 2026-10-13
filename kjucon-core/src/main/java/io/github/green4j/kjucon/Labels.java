/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * The labels and annotations kjucon puts on what a controller creates. The recommended
 * {@code app.kubernetes.io/*} labels tell people and tools who manages an object and what it is part of; the
 * labels and annotations under the operator's own domain are what kjucon finds its objects by.
 */
public final class Labels {
    /** The tool managing the object: the controller's name, unless the controller is told not to set it. */
    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    /** The higher-level whole the object is a part of: the primary resource's name. */
    public static final String PART_OF = "app.kubernetes.io/part-of";
    /** The role of the object within the whole: the component's name. */
    public static final String COMPONENT = "app.kubernetes.io/component";

    /** The domain of the labels, annotations and finalizers, unless the operator is given its own. */
    public static final String DEFAULT_DOMAIN = "kjucon.green4j.github.io";

    private Labels() {
    }

    /**
     * @param domain of the operator
     * @return the label holding the uid of the primary resource which owns the object; what prune selects by
     */
    public static String ownerUid(final String domain) {
        return domain + "/owner-uid";
    }

    /**
     * @param domain of the operator
     * @return the label holding the name of the controller which applies the object; with the owner-uid label,
     *         what tells an object of the controller from one another controller of the same resource applies
     */
    public static String controller(final String domain) {
        return domain + "/controller";
    }

    /**
     * @param domain of the operator
     * @return the annotation holding namespace/name of the owning primary resource, for objects an owner
     *         reference cannot point from: another namespace, cluster scope
     */
    public static String owner(final String domain) {
        return domain + "/owner";
    }

    /**
     * @param domain of the operator
     * @return the annotation holding Kind.group of the owning primary resource
     */
    public static String ownerKind(final String domain) {
        return domain + "/owner-kind";
    }

    /**
     * @param type of a resource
     * @return Kind.group of the type, or the Kind alone for the core group - as the owner-kind annotation holds it
     */
    static String kindOf(final Class<? extends HasMetadata> type) {
        final String group = HasMetadata.getGroup(type);
        final String kind = HasMetadata.getKind(type);
        return group == null || group.isEmpty() ? kind : kind + '.' + group;
    }
}
