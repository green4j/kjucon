/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Leader election on a coordination.k8s.io Lease, so that of several replicas of an operator only one reconciles.
 * The others keep their caches warm and take over when the leader is gone.
 * <p>
 * A leader which loses the lease stops reconciling at once and never takes part in the election again. By
 * default the process then exits with status 1, as controller-runtime and the Java Operator SDK do, so that it is
 * restarted; with {@link #exitOnLost(boolean) exitOnLost(false)} it stays up, its health reporting it not live.
 */
public final class LeaderElection {
    // the defaults of controller-runtime
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RENEW_DEADLINE = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RETRY_PERIOD = Duration.ofSeconds(2);

    private final String namespace;
    private final String name;
    private String identity;
    private Duration leaseDuration = DEFAULT_LEASE_DURATION;
    private Duration renewDeadline = DEFAULT_RENEW_DEADLINE;
    private Duration retryPeriod = DEFAULT_RETRY_PERIOD;
    private Runnable onLost;
    private boolean exitOnLost = true;

    private LeaderElection(final String namespace,
                           final String name) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.name = Objects.requireNonNull(name, "name");
        this.identity = defaultIdentity();
    }

    /**
     * @param namespace of the Lease
     * @param name of the Lease
     * @return a new configuration with the defaults of controller-runtime: 15s lease, 10s renew deadline, 2s retry
     */
    public static LeaderElection lease(final String namespace,
                                       final String name) {
        return new LeaderElection(namespace, name);
    }

    /**
     * @param value of this instance; the pod name (HOSTNAME) by default
     * @return this
     */
    public LeaderElection identity(final String value) {
        this.identity = Objects.requireNonNull(value, "identity");
        return this;
    }

    /**
     * @param value how long a lease is valid without a renewal
     * @return this
     */
    public LeaderElection leaseDuration(final Duration value) {
        this.leaseDuration = Objects.requireNonNull(value, "leaseDuration");
        return this;
    }

    /**
     * @param value how long the leader keeps trying to renew before it gives up
     * @return this
     */
    public LeaderElection renewDeadline(final Duration value) {
        this.renewDeadline = Objects.requireNonNull(value, "renewDeadline");
        return this;
    }

    /**
     * @param value between attempts to acquire or renew
     * @return this
     */
    public LeaderElection retryPeriod(final Duration value) {
        this.retryPeriod = Objects.requireNonNull(value, "retryPeriod");
        return this;
    }

    /**
     * @param action run after the controllers were told to stop on a loss of the leadership, before the exit
     * @return this
     */
    public LeaderElection onLost(final Runnable action) {
        this.onLost = action;
        return this;
    }

    /**
     * @param exit whether the process exits with status 1 once the leadership is lost; true by default
     * @return this
     */
    public LeaderElection exitOnLost(final boolean exit) {
        this.exitOnLost = exit;
        return this;
    }

    String namespace() {
        return namespace;
    }

    String name() {
        return name;
    }

    String identity() {
        return identity;
    }

    Duration leaseDuration() {
        return leaseDuration;
    }

    Duration renewDeadline() {
        return renewDeadline;
    }

    Duration retryPeriod() {
        return retryPeriod;
    }

    Runnable onLost() {
        return onLost;
    }

    boolean exitOnLost() {
        return exitOnLost;
    }

    private static String defaultIdentity() {
        final String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException e) {
            return UUID.randomUUID().toString();
        }
    }
}
