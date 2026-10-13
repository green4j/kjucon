/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import java.util.Objects;

/**
 * How far an object is from doing its job - the states of kstatus, which kubectl and Flux compute the same way.
 */
public final class Readiness {
    /** The states. */
    public enum State {
        /** Doing its job: the desired state is reached and observed. */
        READY,
        /** Getting there: created or changed, not observed or rolled out yet. */
        IN_PROGRESS,
        /** Will not get there without outside help. */
        FAILED,
        /** Not in the cluster. */
        NOT_FOUND,
        /** Being deleted. */
        TERMINATING
    }

    private static final Readiness READY = new Readiness(State.READY, null);
    private static final Readiness NOT_FOUND = new Readiness(State.NOT_FOUND, null);
    private static final Readiness TERMINATING = new Readiness(State.TERMINATING, null);

    private final State state;
    private final String message;

    private Readiness(final State state,
                      final String message) {
        this.state = state;
        this.message = message;
    }

    /**
     * @return ready
     */
    public static Readiness ready() {
        return READY;
    }

    /**
     * @param message what is awaited
     * @return in progress
     */
    public static Readiness inProgress(final String message) {
        return new Readiness(State.IN_PROGRESS, message);
    }

    /**
     * @param message what failed
     * @return failed
     */
    public static Readiness failed(final String message) {
        return new Readiness(State.FAILED, message);
    }

    /**
     * @return not found
     */
    public static Readiness notFound() {
        return NOT_FOUND;
    }

    /**
     * @return terminating
     */
    public static Readiness terminating() {
        return TERMINATING;
    }

    /**
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * @return what is awaited, or what failed; may be null
     */
    public String message() {
        return message;
    }

    /**
     * @return true if {@link State#READY}
     */
    public boolean isReady() {
        return state == State.READY;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Readiness)) {
            return false;
        }
        final Readiness that = (Readiness) o;
        return state == that.state && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, message);
    }

    @Override
    public String toString() {
        return message == null ? state.name() : state.name() + ": " + message;
    }
}
