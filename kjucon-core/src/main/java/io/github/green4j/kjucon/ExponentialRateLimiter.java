/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class ExponentialRateLimiter implements RateLimiter {
    static final Duration DEFAULT_BASE = Duration.ofMillis(500);
    static final Duration DEFAULT_MAX = Duration.ofMinutes(5);
    private static final int MAX_SHIFT = 30;
    private static final int JITTER_DIVISOR = 10;

    private final long baseMillis;
    private final long maxMillis;
    private final Map<ReconcileRequest, Integer> failures = new ConcurrentHashMap<>();

    ExponentialRateLimiter(final Duration base,
                           final Duration max) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(max, "max");
        if (base.isNegative() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("Expected 0 <= base <= max, got " + base + " and " + max);
        }
        this.baseMillis = base.toMillis();
        this.maxMillis = max.toMillis();
    }

    @Override
    public Duration next(final ReconcileRequest request,
                         final boolean permanent) {
        final int attempt = failures.merge(request, 1, Integer::sum);
        if (permanent || baseMillis == maxMillis) {
            return Duration.ofMillis(maxMillis);
        }
        final int shift = Math.min(attempt - 1, MAX_SHIFT);
        final long exponential = baseMillis << shift;
        final long delay = exponential <= 0 || exponential > maxMillis ? maxMillis : exponential;
        final long jitter = ThreadLocalRandom.current().nextLong(delay / JITTER_DIVISOR + 1);
        return Duration.ofMillis(Math.min(delay + jitter, maxMillis));
    }

    @Override
    public int failures(final ReconcileRequest request) {
        return failures.getOrDefault(request, 0);
    }

    @Override
    public void forget(final ReconcileRequest request) {
        failures.remove(request);
    }

    @Override
    public int failing() {
        return failures.size();
    }
}
