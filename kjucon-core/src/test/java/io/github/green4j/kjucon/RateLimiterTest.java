/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.github.green4j.kjucon.test.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    private static final ReconcileRequest A = new ReconcileRequest(Fixtures.NAMESPACE, "a");
    private static final ReconcileRequest B = new ReconcileRequest(Fixtures.NAMESPACE, "b");
    private static final Duration BASE = Duration.ofMillis(100);
    // reached on the fifth failure: 100, 200, 400, 800, then capped
    private static final Duration MAX = Duration.ofSeconds(1);
    private static final int DOUBLINGS_BELOW_MAX = 4;
    // the jitter is up to a tenth of the delay
    private static final double MAX_JITTER = 0.1;
    // far more than it takes to overflow a shifted long
    private static final int MANY_FAILURES = 100;

    @Test
    void backsOffExponentiallyWithBoundedJitter() {
        final RateLimiter limiter = RateLimiter.exponential(BASE, MAX);
        Duration expected = BASE;
        for (int i = 0; i < DOUBLINGS_BELOW_MAX; i++) {
            assertJittered(expected, limiter.next(A, false));
            expected = expected.multipliedBy(2);
        }
        assertEquals(MAX, limiter.next(A, false));
        assertEquals(MAX, limiter.next(A, false));
        assertEquals(DOUBLINGS_BELOW_MAX + 2, limiter.failures(A));
    }

    @Test
    void countsPerRequestAndForgets() {
        final RateLimiter limiter = RateLimiter.exponential(BASE, MAX);
        limiter.next(A, false);
        limiter.next(A, false);
        assertJittered(BASE, limiter.next(B, false));
        assertEquals(2, limiter.failing());

        limiter.forget(A);
        assertEquals(0, limiter.failures(A));
        assertJittered(BASE, limiter.next(A, false));
    }

    @Test
    void permanentFailureWaitsTheMaximum() {
        final RateLimiter limiter = RateLimiter.exponential(BASE, MAX);
        assertEquals(MAX, limiter.next(A, true));
    }

    @Test
    void doesNotOverflowOnManyFailures() {
        final RateLimiter limiter = RateLimiter.defaults();
        Duration last = Duration.ZERO;
        for (int i = 0; i < MANY_FAILURES; i++) {
            last = limiter.next(A, false);
        }
        assertEquals(ExponentialRateLimiter.DEFAULT_MAX, last);
    }

    private static void assertJittered(final Duration expected,
                                       final Duration actual) {
        final long min = expected.toMillis();
        final long max = Math.round(min * (1 + MAX_JITTER));
        assertTrue(actual.toMillis() >= min && actual.toMillis() <= max,
                "expected " + min + ".." + max + " ms, got " + actual.toMillis());
    }
}
