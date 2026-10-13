/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.test;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * Retries a write which may meet a 409 Conflict - a test changing an object its controller writes concurrently,
 * as every client of a live object must.
 */
public final class Conflicts {
    private static final int CONFLICT = 409;
    private static final int ATTEMPTS = 50;

    private Conflicts() {
    }

    /**
     * @param write to run - a read-modify-write of one object
     */
    public static void retry(final Runnable write) {
        for (int attempt = 1; ; attempt++) {
            try {
                write.run();
                return;
            } catch (final KubernetesClientException e) {
                if (e.getCode() != CONFLICT || attempt >= ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
