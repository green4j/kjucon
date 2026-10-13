/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Asks the API server for its version on a schedule, so that the readiness of the operator says whether it can
 * reach the cluster even when nothing is being reconciled.
 */
final class ApiServerProbe implements AutoCloseable {
    private final KubernetesClient client;
    private final Duration interval;

    private ScheduledExecutorService scheduler;
    private volatile Boolean reachable;
    private volatile String message;

    ApiServerProbe(final KubernetesClient client,
                   final Duration interval) {
        this.client = client;
        this.interval = interval;
    }

    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "kjucon#api-server-probe");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::probe, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    Boolean reachable() {
        return reachable;
    }

    String message() {
        return message;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void probe() {
        try {
            final VersionInfo version = client.getKubernetesVersion();
            message = version != null ? version.getGitVersion() : "reachable";
            reachable = Boolean.TRUE;
        } catch (final RuntimeException e) {
            message = e.toString();
            reachable = Boolean.FALSE;
        }
    }
}
