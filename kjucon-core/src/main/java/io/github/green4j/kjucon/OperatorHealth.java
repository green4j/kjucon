/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.util.List;

/**
 * A snapshot of the state of an operator, for liveness and readiness probes. There is no HTTP server in kjucon:
 * whatever serves the probes asks {@link Operator#health()} and answers from this.
 * <p>
 * Failures of reconciles are deliberately not part of it. One invalid resource must not make the whole operator
 * restart; those are what {@link ControllerHealth#failing()} and the {@link ErrorHandler} are for.
 */
public final class OperatorHealth {
    private static final String NOT_APPLICABLE = "n/a";

    private final boolean started;
    private final boolean leaderElection;
    private final boolean leader;
    private final boolean leadershipLost;
    private final boolean informersSynced;
    private final List<String> failedInformers;
    private final Boolean apiServerReachable;
    private final String apiServerMessage;
    private final List<ControllerHealth> controllers;

    OperatorHealth(final boolean started,
                   final boolean leaderElection,
                   final boolean leader,
                   final boolean leadershipLost,
                   final boolean informersSynced,
                   final List<String> failedInformers,
                   final Boolean apiServerReachable,
                   final String apiServerMessage,
                   final List<ControllerHealth> controllers) {
        this.started = started;
        this.leaderElection = leaderElection;
        this.leader = leader;
        this.leadershipLost = leadershipLost;
        this.informersSynced = informersSynced;
        this.failedInformers = List.copyOf(failedInformers);
        this.apiServerReachable = apiServerReachable;
        this.apiServerMessage = apiServerMessage;
        this.controllers = List.copyOf(controllers);
    }

    /**
     * Live: started, and nothing happened which only a restart can fix - no informer stopped for good, no
     * leadership lost.
     *
     * @return true if the process should be left running
     */
    public boolean isLive() {
        return started && !leadershipLost && failedInformers.isEmpty();
    }

    /**
     * Ready: live, every informer synced, the API server reachable (if probed), and - with leader election -
     * leading. A standby replica is live but not ready.
     *
     * @return true if the operator is doing its work
     */
    public boolean isReady() {
        return isLive()
                && informersSynced
                && !Boolean.FALSE.equals(apiServerReachable)
                && (!leaderElection || leader);
    }

    /**
     * @return true once {@link Operator#start()} was called, and until {@link Operator#close()}
     */
    public boolean started() {
        return started;
    }

    /**
     * @return true if the operator takes part in a leader election
     */
    public boolean leaderElection() {
        return leaderElection;
    }

    /**
     * @return true if this instance is the leader; always false without leader election
     */
    public boolean leader() {
        return leader;
    }

    /**
     * @return true if this instance was the leader and lost it
     */
    public boolean leadershipLost() {
        return leadershipLost;
    }

    /**
     * @return true if every informer has listed everything and is watching
     */
    public boolean informersSynced() {
        return informersSynced;
    }

    /**
     * @return the types whose informers stopped and will not recover
     */
    public List<String> failedInformers() {
        return failedInformers;
    }

    /**
     * @return whether the last probe of the API server succeeded; null if it is not probed, or not yet
     */
    public Boolean apiServerReachable() {
        return apiServerReachable;
    }

    /**
     * @return what the last probe of the API server said: its version, or the failure; null if not probed
     */
    public String apiServerMessage() {
        return apiServerMessage;
    }

    /**
     * @return the state of each controller
     */
    public List<ControllerHealth> controllers() {
        return controllers;
    }

    @Override
    public String toString() {
        return "OperatorHealth{live=" + isLive()
                + ", ready=" + isReady()
                + ", leader=" + (leaderElection ? String.valueOf(leader) : NOT_APPLICABLE)
                + ", informersSynced=" + informersSynced
                + ", failedInformers=" + failedInformers
                + ", apiServer=" + (apiServerReachable == null ? NOT_APPLICABLE : apiServerMessage)
                + ", controllers=" + controllers
                + '}';
    }
}
