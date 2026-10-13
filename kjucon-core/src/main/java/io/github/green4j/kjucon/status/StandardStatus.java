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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A ready-made status for custom resources: a phase for people and {@code kubectl get}, conditions for
 * tooling, and the generation it was computed for. A resource declared as
 * {@code CustomResource<MySpec, StandardStatus>} is understood by kjucon without any further code: a parent
 * composing it sees when it is ready.
 * <p>
 * The helpers deliberately avoid bean naming ({@code markReady()}, {@code hasPhase(..)}), so that they never
 * show up as properties of the serialized status.
 */
public class StandardStatus {
    /** Being created or changed: not everything is ready yet. */
    public static final String PENDING = "Pending";
    /** Everything is ready. */
    public static final String READY = "Ready";
    /** The last reconcile failed, or the desired state cannot be reached. */
    public static final String ERROR = "Error";
    /** Being deleted: what the resource owns is being removed. */
    public static final String DELETING = "Deleting";

    private String phase;
    private String message;
    private Long observedGeneration;
    private String lastUpdateTime;
    private List<Condition> conditions = new ArrayList<>();

    public String getPhase() {
        return phase;
    }

    public void setPhase(final String phase) {
        this.phase = phase;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(final Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    /**
     * @return when the status last changed - not when the resource was last reconciled: a reconcile which
     *         changes nothing writes nothing
     */
    public String getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(final String lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(final List<Condition> conditions) {
        this.conditions = conditions != null ? conditions : new ArrayList<>();
    }

    /**
     * Sets the phase, the message and the generation they are for, and the conditions which go with the phase:
     * Ready, Reconciling and Stalled.
     *
     * @param newPhase one of the phase constants
     * @param newMessage may be null
     * @param generation of the resource the status is computed for, may be null
     * @return true if anything changed - the update time then too
     */
    public boolean update(final String newPhase,
                          final String newMessage,
                          final Long generation) {
        boolean changed = !Objects.equals(phase, newPhase)
                || !Objects.equals(message, newMessage)
                || !Objects.equals(observedGeneration, generation);
        phase = newPhase;
        message = newMessage;
        observedGeneration = generation;
        final boolean ready = READY.equals(newPhase);
        final boolean error = ERROR.equals(newPhase);
        changed |= Conditions.set(conditions, Conditions.READY,
                ready ? Conditions.TRUE : Conditions.FALSE, newPhase, newMessage, generation);
        changed |= Conditions.set(conditions, Conditions.RECONCILING,
                ready || error ? Conditions.FALSE : Conditions.TRUE, newPhase, null, generation);
        changed |= Conditions.set(conditions, Conditions.STALLED,
                error ? Conditions.TRUE : Conditions.FALSE, newPhase, error ? newMessage : null, generation);
        if (changed) {
            lastUpdateTime = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        return changed;
    }

    /**
     * @param generation the status is for
     * @return see {@link #update(String, String, Long)}
     */
    public boolean markPending(final Long generation) {
        return update(PENDING, null, generation);
    }

    /**
     * @param generation the status is for
     * @return see {@link #update(String, String, Long)}
     */
    public boolean markReady(final Long generation) {
        return update(READY, null, generation);
    }

    /**
     * @param reason of the error
     * @param generation the status is for
     * @return see {@link #update(String, String, Long)}
     */
    public boolean markError(final String reason,
                             final Long generation) {
        return update(ERROR, reason, generation);
    }

    /**
     * @param generation the status is for
     * @return see {@link #update(String, String, Long)}
     */
    public boolean markDeleting(final Long generation) {
        return update(DELETING, null, generation);
    }

    /**
     * @param expected phase
     * @return true if the status is in that phase
     */
    public boolean hasPhase(final String expected) {
        return expected.equals(phase);
    }

    @Override
    public String toString() {
        return "StandardStatus{phase='" + phase + '\''
                + ", message='" + message + '\''
                + ", observedGeneration=" + observedGeneration
                + ", lastUpdateTime='" + lastUpdateTime + '\''
                + '}';
    }
}
