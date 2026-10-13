/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool helpers.
 */
final class Concurrency {

    private Concurrency() {
    }

    /**
     * Shuts the executor down and waits for it to terminate, for a bounded time. Tasks still running when the
     * timeout passes are interrupted, and waited for once more as long; a task which ignores the interrupt is
     * left behind. An interrupt of the calling thread is not lost: it is restored before returning.
     *
     * @param service to shut down
     * @param timeoutMillis to wait for running tasks before interrupting them, and after
     * @return true if the executor terminated
     */
    static boolean shutdown(final ExecutorService service,
                            final long timeoutMillis) {
        if (service.isTerminated()) {
            return true;
        }
        service.shutdown();
        boolean interrupted = false;
        boolean terminated = false;
        for (int round = 0; round < 2 && !terminated; round++) {
            try {
                terminated = service.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                interrupted = true;
            }
            if (!terminated) {
                service.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return terminated;
    }
}
