/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon;

import io.github.green4j.kjucon.test.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkQueueTest {
    private static final ReconcileRequest A = new ReconcileRequest("ns", "a");
    private static final ReconcileRequest B = new ReconcileRequest("ns", "b");

    private final WorkQueue queue = new WorkQueue("test");

    @AfterEach
    void close() {
        queue.close();
    }

    @Test
    void deduplicatesQueuedRequests() throws InterruptedException {
        assertTrue(queue.add(A));
        assertFalse(queue.add(A));
        assertTrue(queue.add(B));
        assertEquals(2, queue.depth());

        assertEquals(A, queue.poll(0));
        assertEquals(B, queue.poll(0));
        assertNull(queue.poll(0));
    }

    @Test
    void requestAddedWhileProcessingIsQueuedAgainWhenDone() throws InterruptedException {
        queue.add(A);
        assertEquals(A, queue.poll(0));

        assertTrue(queue.add(A), "marked dirty");
        assertEquals(0, queue.depth(), "not handed to another worker while processing");
        assertFalse(queue.add(A), "already dirty");

        queue.done(A);
        assertEquals(1, queue.depth());
        assertEquals(A, queue.poll(0));
        queue.done(A);
        assertNull(queue.poll(0));
    }

    @Test
    void delayedAdditionArrivesAfterTheDelay() throws InterruptedException {
        queue.addAfter(A, Duration.ofMillis(100));
        assertNull(queue.poll(0));
        assertEquals(1, queue.delayed());
        assertEquals(A, queue.poll(2_000));
        assertEquals(0, queue.delayed());
    }

    @Test
    void earliestDelayedAdditionWins() throws InterruptedException {
        queue.addAfter(A, Duration.ofSeconds(30));
        queue.addAfter(A, Duration.ofMillis(50));
        queue.addAfter(A, Duration.ofSeconds(60));
        assertEquals(A, queue.poll(2_000));
        assertEquals(0, queue.delayed());
    }

    @Test
    void removeDropsQueuedAndDelayed() throws InterruptedException {
        queue.add(A);
        queue.addAfter(B, Duration.ofMillis(50));
        queue.remove(A);
        queue.remove(B);
        assertEquals(0, queue.depth());
        assertEquals(0, queue.delayed());
        Thread.sleep(150);
        assertNull(queue.poll(0));
    }

    @Test
    void removeDuringProcessingCancelsTheRepeat() throws InterruptedException {
        queue.add(A);
        queue.poll(0);
        queue.add(A);
        queue.remove(A);
        queue.done(A);
        assertNull(queue.poll(0));
    }

    @Test
    void closeWakesWaitingWorkers() throws InterruptedException {
        final ReconcileRequest[] taken = {A};
        final Thread worker = new Thread(() -> {
            try {
                taken[0] = queue.poll(60_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(50);
        queue.close();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertNull(taken[0]);
        assertFalse(queue.add(A));
    }

    @Test
    void blockedPollIsWokenByAnAddition() throws InterruptedException {
        final ReconcileRequest[] taken = new ReconcileRequest[1];
        final Thread worker = new Thread(() -> {
            try {
                taken[0] = queue.poll(10_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(50);
        queue.add(B);
        Await.until(() -> !worker.isAlive(), "the worker to take the request");
        assertEquals(B, taken[0]);
    }
}
