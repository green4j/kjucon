/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The queue of a controller, with the guarantees of the client-go work queue:
 * <ul>
 *   <li>a request is queued at most once, however many times it is added before it is taken;</li>
 *   <li>a request is never processed by two workers at once;</li>
 *   <li>a request added while it is being processed is not lost: it is marked dirty and queued again when
 *   the processing is {@link #done(ReconcileRequest)};</li>
 *   <li>of several delayed additions of one request, the earliest wins.</li>
 * </ul>
 */
final class WorkQueue implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private final ArrayDeque<ReconcileRequest> queue = new ArrayDeque<>();
    private final Set<ReconcileRequest> dirty = new HashSet<>();
    private final Set<ReconcileRequest> processing = new HashSet<>();
    private final Map<ReconcileRequest, Waiting> waiting = new HashMap<>();

    private final ScheduledExecutorService scheduler;

    private boolean shutdown;

    WorkQueue(final String name) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, name + "#delay");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @param request to add
     * @return true if it was queued, or marked to be queued again; false if it already was, or the queue is shut
     *         down
     */
    boolean add(final ReconcileRequest request) {
        lock.lock();
        try {
            if (shutdown || !dirty.add(request)) {
                return false;
            }
            if (!processing.contains(request)) {
                queue.addLast(request);
                notEmpty.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds the request once the delay has passed - unless it is to be added earlier anyway.
     *
     * @param request to add
     * @param delay to add after
     */
    void addAfter(final ReconcileRequest request,
                  final Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            add(request);
            return;
        }
        lock.lock();
        try {
            if (shutdown) {
                return;
            }
            final long deadline = System.nanoTime() + delay.toNanos();
            final Waiting existing = waiting.get(request);
            if (existing != null) {
                if (existing.deadline - deadline <= 0) {
                    return;
                }
                existing.future.cancel(false);
            }
            final Waiting entry = new Waiting(deadline);
            entry.future = scheduler.schedule(() -> fire(request, entry), delay.toNanos(), TimeUnit.NANOSECONDS);
            waiting.put(request, entry);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the next request, waiting for one up to the timeout.
     *
     * @param timeoutMillis to wait
     * @return the request, now being processed until {@link #done(ReconcileRequest)}; null on timeout or shutdown
     * @throws InterruptedException if interrupted while waiting
     */
    ReconcileRequest poll(final long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (queue.isEmpty()) {
                if (shutdown || nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown) {
                return null;
            }
            final ReconcileRequest request = queue.pollFirst();
            processing.add(request);
            dirty.remove(request);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the processing of a request; queues it again if it was added in the meantime.
     *
     * @param request processed
     */
    void done(final ReconcileRequest request) {
        lock.lock();
        try {
            processing.remove(request);
            if (dirty.contains(request) && !shutdown) {
                queue.addLast(request);
                notEmpty.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops whatever is queued or scheduled for the request - its resource is gone. A processing in progress
     * is left to finish, but is not repeated.
     *
     * @param request to drop
     */
    void remove(final ReconcileRequest request) {
        lock.lock();
        try {
            final Waiting existing = waiting.remove(request);
            if (existing != null) {
                existing.future.cancel(false);
            }
            if (dirty.remove(request)) {
                queue.remove(request);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the requests waiting to be taken, not counting the delayed ones
     */
    int depth() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the requests scheduled for later
     */
    int delayed() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            for (final Waiting entry : waiting.values()) {
                entry.future.cancel(false);
            }
            waiting.clear();
            queue.clear();
            dirty.clear();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        scheduler.shutdownNow();
    }

    private void fire(final ReconcileRequest request,
                      final Waiting entry) {
        lock.lock();
        try {
            if (waiting.get(request) != entry) {
                return; // replaced by an earlier one, or removed
            }
            waiting.remove(request);
        } finally {
            lock.unlock();
        }
        add(request);
    }

    private static final class Waiting {
        private final long deadline;
        private ScheduledFuture<?> future;

        private Waiting(final long deadline) {
            this.deadline = deadline;
        }
    }
}
