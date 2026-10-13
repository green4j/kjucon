/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.status;

import io.fabric8.kubernetes.api.model.Condition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardStatusTest {

    @Test
    void phasesSetMatchingConditions() {
        final StandardStatus status = new StandardStatus();
        assertTrue(status.markPending(1L));
        assertTrue(Conditions.isTrue(status.getConditions(), Conditions.RECONCILING));
        assertFalse(Conditions.isTrue(status.getConditions(), Conditions.READY));

        assertTrue(status.markReady(1L));
        assertTrue(Conditions.isTrue(status.getConditions(), Conditions.READY));
        assertFalse(Conditions.isTrue(status.getConditions(), Conditions.RECONCILING));
        assertFalse(Conditions.isTrue(status.getConditions(), Conditions.STALLED));
        assertTrue(status.hasPhase(StandardStatus.READY));

        assertTrue(status.markError("broken", 2L));
        assertTrue(Conditions.isTrue(status.getConditions(), Conditions.STALLED));
        assertEquals(2L, status.getObservedGeneration());
    }

    @Test
    void reportsNoChangeForTheSameState() {
        final StandardStatus status = new StandardStatus();
        status.markReady(3L);
        assertFalse(status.markReady(3L));
        assertTrue(status.markReady(4L));
    }

    @Test
    void transitionTimeMovesOnlyWithTheStatus() {
        final StandardStatus status = new StandardStatus();
        status.markPending(1L);
        final Condition ready = Conditions.find(status.getConditions(), Conditions.READY);
        ready.setLastTransitionTime("2000-01-01T00:00:00Z");

        status.update(StandardStatus.PENDING, "still", 1L);
        assertEquals("2000-01-01T00:00:00Z", ready.getLastTransitionTime(), "status unchanged");

        status.markReady(1L);
        assertFalse("2000-01-01T00:00:00Z".equals(ready.getLastTransitionTime()));
    }
}
