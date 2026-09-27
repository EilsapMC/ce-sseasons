package dev.ceseasons.climate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SamplingBudgetTest {
    @Test
    void budgetIsSharedAcrossPlayersAndDoesNotRefundTheCurrentRound() {
        SamplingBudget budget = new SamplingBudget(3);
        budget.beginRound();
        var first = budget.reserve(UUID.randomUUID(), 2);
        var second = budget.reserve(UUID.randomUUID(), 2);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, first.samples());
        assertEquals(1, second.samples());
        assertNull(budget.reserve(UUID.randomUUID(), 1));
        first.close();
        assertEquals(1, budget.outstanding());
        assertNull(budget.reserve(UUID.randomUUID(), 1));
        second.close();
        assertEquals(0, budget.outstanding());
        assertTrue(budget.exhausted());
        budget.beginRound();
        assertFalse(budget.exhausted());
    }

    @Test
    void pendingPlayersAreDeduplicatedAcrossRoundsAndRemainBounded() {
        SamplingBudget budget = new SamplingBudget(4);
        UUID player = UUID.randomUUID();
        budget.beginRound();
        var first = budget.reserve(player, 2);
        assertNotNull(first);
        budget.beginRound();
        assertNull(budget.reserve(player, 2));
        var second = budget.reserve(UUID.randomUUID(), 4);
        assertNotNull(second);
        assertEquals(2, second.samples());
        for (int i = 0; i < 100; i++) {
            budget.beginRound();
            assertNull(budget.reserve(UUID.randomUUID(), 1));
        }
        assertEquals(4, budget.outstanding());
        first.close();
        second.close();
    }

    @Test
    void doubleRetirementCannotReleaseNewPermitForSamePlayer() {
        SamplingBudget budget = new SamplingBudget(2);
        UUID player = UUID.randomUUID();
        budget.beginRound();
        var old = budget.reserve(player, 2);
        assertNotNull(old);
        old.close();
        budget.beginRound();
        var current = budget.reserve(player, 2);
        assertNotNull(current);
        old.close();
        assertEquals(2, budget.outstanding());
        current.close();
        current.close();
        assertEquals(0, budget.outstanding());
    }

    @Test
    void reservationsAndRetirementsAreThreadSafe() throws Exception {
        SamplingBudget budget = new SamplingBudget(64);
        budget.beginRound();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<SamplingBudget.Permit>>();
            for (int i = 0; i < 200; i++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return budget.reserve(UUID.randomUUID(), 2);
                }));
            }
            start.countDown();
            List<SamplingBudget.Permit> permits = new ArrayList<>();
            for (var future : futures) {
                var permit = future.get(10, TimeUnit.SECONDS);
                if (permit != null) {
                    permits.add(permit);
                }
            }
            assertEquals(64, permits.stream().mapToInt(SamplingBudget.Permit::samples).sum());
            assertEquals(64, budget.outstanding());
            var releases = new ArrayList<java.util.concurrent.Future<?>>();
            permits.forEach(permit -> releases.add(executor.submit(permit::close)));
            for (var future : releases) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, budget.outstanding());
    }

    @Test
    void replacementEpochDoesNotShareRetiredBudget() {
        SamplingBudget old = new SamplingBudget(2);
        SamplingBudget current = new SamplingBudget(2);
        UUID player = UUID.randomUUID();
        old.beginRound();
        current.beginRound();
        var previous = old.reserve(player, 2);
        var next = current.reserve(player, 2);
        assertNotNull(previous);
        assertNotNull(next);
        previous.close();
        assertEquals(2, current.outstanding());
        next.close();
    }
}
