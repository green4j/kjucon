/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Duration;

/**
 * Decides how long a failed request waits before it is retried. Failures are counted per request, so one
 * resource failing over and over backs off on its own while the others go on at full speed.
 */
public interface RateLimiter {

    /**
     * Records a failure and tells when to retry.
     *
     * @param request which failed
     * @param permanent whether the failure is one a retry is unlikely to fix (see
     *                  {@link io.github.green4j.kjucon.util.Errors#isPermanent(Throwable)})
     * @return the delay before the retry
     */
    Duration next(ReconcileRequest request,
                  boolean permanent);

    /**
     * @param request to check
     * @return the failures recorded for it since it was last forgotten
     */
    int failures(ReconcileRequest request);

    /**
     * Forgets the failures of a request - it has succeeded, or is gone.
     *
     * @param request to forget
     */
    void forget(ReconcileRequest request);

    /**
     * @return how many requests have failures recorded - those waiting for a retry
     */
    int failing();

    /**
     * The default: from half a second, doubling up to five minutes.
     *
     * @return a new limiter
     */
    static RateLimiter defaults() {
        return exponential(ExponentialRateLimiter.DEFAULT_BASE, ExponentialRateLimiter.DEFAULT_MAX);
    }

    /**
     * An exponential back-off: base, 2 * base, 4 * base... up to max, each with up to 10% of random jitter so
     * that resources failing together do not retry together. A permanent failure waits max at once.
     *
     * @param base delay after the first failure
     * @param max delay
     * @return a new limiter
     */
    static RateLimiter exponential(final Duration base,
                                   final Duration max) {
        return new ExponentialRateLimiter(base, max);
    }

    /**
     * The same delay after every failure.
     *
     * @param delay after a failure
     * @return a new limiter
     */
    static RateLimiter fixed(final Duration delay) {
        return new ExponentialRateLimiter(delay, delay);
    }
}
