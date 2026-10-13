/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Instant;

/**
 * A snapshot of the state of one controller.
 */
public final class ControllerHealth {
    private final String name;
    private final int workers;
    private final int workersAlive;
    private final int queueDepth;
    private final int delayed;
    private final int failing;
    private final Instant lastSuccess;

    ControllerHealth(final String name,
                     final int workers,
                     final int workersAlive,
                     final int queueDepth,
                     final int delayed,
                     final int failing,
                     final Instant lastSuccess) {
        this.name = name;
        this.workers = workers;
        this.workersAlive = workersAlive;
        this.queueDepth = queueDepth;
        this.delayed = delayed;
        this.failing = failing;
        this.lastSuccess = lastSuccess;
    }

    /**
     * @return the name of the controller
     */
    public String name() {
        return name;
    }

    /**
     * @return the workers configured
     */
    public int workers() {
        return workers;
    }

    /**
     * @return the workers running; fewer than configured only before the start and after the stop
     */
    public int workersAlive() {
        return workersAlive;
    }

    /**
     * @return requests waiting to be taken
     */
    public int queueDepth() {
        return queueDepth;
    }

    /**
     * @return requests scheduled for later - requeues, resyncs and retries
     */
    public int delayed() {
        return delayed;
    }

    /**
     * @return requests whose last reconcile failed
     */
    public int failing() {
        return failing;
    }

    /**
     * @return when a reconcile last succeeded, null if none has yet
     */
    public Instant lastSuccess() {
        return lastSuccess;
    }

    @Override
    public String toString() {
        return name + "{workers=" + workersAlive + '/' + workers
                + ", queueDepth=" + queueDepth
                + ", delayed=" + delayed
                + ", failing=" + failing
                + ", lastSuccess=" + lastSuccess
                + '}';
    }
}
