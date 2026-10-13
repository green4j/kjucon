/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.status;

import io.fabric8.kubernetes.api.model.Condition;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a list of status conditions the way the Kubernetes API conventions describe: one entry per type,
 * {@code lastTransitionTime} moved only when the status really changes, {@code observedGeneration} recorded
 * with each.
 */
public final class Conditions {
    /** The condition status of a condition which holds. */
    public static final String TRUE = "True";
    /** The condition status of a condition which does not hold. */
    public static final String FALSE = "False";
    /** The condition status of a condition which cannot be determined. */
    public static final String UNKNOWN = "Unknown";

    /** The resource is fully operational. */
    public static final String READY = "Ready";
    /** The controller is working towards the desired state. */
    public static final String RECONCILING = "Reconciling";
    /** The controller cannot reach the desired state without outside help. */
    public static final String STALLED = "Stalled";

    private Conditions() {
    }

    /**
     * Sets a condition.
     *
     * @param conditions to change
     * @param type of the condition
     * @param status one of {@link #TRUE}, {@link #FALSE}, {@link #UNKNOWN}
     * @param reason a CamelCase reason; null stands for the status itself
     * @param message a human readable message; null stands for an empty one
     * @param observedGeneration of the resource the condition was computed for, may be null
     * @return true if anything changed
     */
    public static boolean set(final List<Condition> conditions,
                              final String type,
                              final String status,
                              final String reason,
                              final String message,
                              final Long observedGeneration) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        // metav1.Condition requires both: a CRD using its schema rejects a condition without them
        return doSet(conditions, type, status, reason != null ? reason : status, message != null ? message : "",
                observedGeneration);
    }

    private static boolean doSet(final List<Condition> conditions,
                                 final String type,
                                 final String status,
                                 final String reason,
                                 final String message,
                                 final Long observedGeneration) {
        final Condition existing = find(conditions, type);
        if (existing == null) {
            final Condition condition = new Condition();
            condition.setType(type);
            condition.setStatus(status);
            condition.setReason(reason);
            condition.setMessage(message);
            condition.setObservedGeneration(observedGeneration);
            condition.setLastTransitionTime(now());
            conditions.add(condition);
            return true;
        }
        boolean changed = false;
        if (!status.equals(existing.getStatus())) {
            existing.setStatus(status);
            existing.setLastTransitionTime(now());
            changed = true;
        }
        if (!Objects.equals(reason, existing.getReason())) {
            existing.setReason(reason);
            changed = true;
        }
        if (!Objects.equals(message, existing.getMessage())) {
            existing.setMessage(message);
            changed = true;
        }
        if (!Objects.equals(observedGeneration, existing.getObservedGeneration())) {
            existing.setObservedGeneration(observedGeneration);
            changed = true;
        }
        return changed;
    }

    /**
     * Removes a condition.
     *
     * @param conditions to change
     * @param type of the condition
     * @return true if it was there
     */
    public static boolean remove(final List<Condition> conditions,
                                 final String type) {
        return conditions != null && conditions.removeIf(c -> type.equals(c.getType()));
    }

    /**
     * @param conditions to look in, may be null
     * @param type of the condition
     * @return the condition, or null
     */
    public static Condition find(final List<Condition> conditions,
                                 final String type) {
        if (conditions == null) {
            return null;
        }
        for (final Condition condition : conditions) {
            if (type.equals(condition.getType())) {
                return condition;
            }
        }
        return null;
    }

    /**
     * @param conditions to look in, may be null
     * @param type of the condition
     * @return true if the condition is there with the status {@link #TRUE}
     */
    public static boolean isTrue(final List<Condition> conditions,
                                 final String type) {
        final Condition condition = find(conditions, type);
        return condition != null && TRUE.equals(condition.getStatus());
    }

    private static String now() {
        // seconds: the precision of metav1.Time, so a re-read compares equal
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
