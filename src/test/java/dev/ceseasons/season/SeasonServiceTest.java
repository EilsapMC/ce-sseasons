package dev.ceseasons.season;

import dev.ceseasons.storage.SeasonStore.StoredSeason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SeasonServiceTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long SUB_TICKS = 24_000L * 8;

    private static SeasonService service(boolean progressWhileEmpty) {
        return new SeasonService(24_000, 8, 1, progressWhileEmpty, Map.of());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void fixedStartIsOneBased(int startingStage) {
        SeasonService service = new SeasonService(24_000, 8, startingStage, true, Map.of());
        assertEquals(SubSeason.values()[startingStage - 1], service.bindWorld(WORLD).subSeason());
        assertEquals((startingStage - 1L) * SUB_TICKS, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void randomStartupChoiceIsReusedForAllNewWorldsAndRebinds() {
        SeasonService service = new SeasonService(24_000, 8, 0, true, Map.of());
        SeasonSnapshot first = service.bindWorld(WORLD);
        for (int index = 10; index < 74; index++) {
            assertEquals(first.cycleTicks(), service.bindWorld(new UUID(0, index)).cycleTicks());
        }
        service.tick(100, true);
        service.tick(150, true);
        service.unbindWorld(WORLD);
        assertEquals(first.cycleTicks() + 50, service.bindWorld(WORLD).cycleTicks());
        assertEquals(first.cycleTicks(), service.bindWorld(OTHER).cycleTicks());
    }

    @Test
    void firstClockSampleDoesNotChargeServerUptime() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(Long.MAX_VALUE - 1, true);
        assertEquals(0, service.snapshot(WORLD).cycleTicks());
        service.tick(Long.MAX_VALUE, true);
        assertEquals(1, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void pauseConsumesEveryClockSampleWithoutCatchup() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(100, true);
        service.tick(200, true);
        service.setPaused(WORLD, true);
        long revision = service.snapshot(WORLD).revision();
        service.tick(20_000, true);
        service.tick(40_000, true);
        assertEquals(100, service.snapshot(WORLD).cycleTicks());
        assertEquals(revision, service.snapshot(WORLD).revision());
        service.setPaused(WORLD, false);
        service.tick(40_010, true);
        assertEquals(110, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void emptyServerConsumesEveryClockSampleWithoutCatchup() {
        SeasonService service = service(false);
        service.bindWorld(WORLD);
        service.tick(100, true);
        service.tick(10_000, false);
        service.tick(30_000, false);
        assertEquals(0, service.snapshot(WORLD).cycleTicks());
        service.tick(30_010, true);
        assertEquals(10, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void emptyServerCanProgressWhenConfigured() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(100, false);
        service.tick(200, false);
        assertEquals(100, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void pausedWorldDoesNotFreezeOtherWorlds() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.bindWorld(OTHER);
        service.setPaused(WORLD, true);
        service.tick(0, true);
        service.tick(500, true);
        assertEquals(0, service.snapshot(WORLD).cycleTicks());
        assertEquals(500, service.snapshot(OTHER).cycleTicks());
    }

    @Test
    void unloadedAndNeverLoadedWorldsRetainPersistence() {
        SeasonService service = new SeasonService(24_000, 8, 1, true,
                Map.of(WORLD, new StoredSeason(123, true), OTHER, new StoredSeason(456, false)));
        assertNull(service.snapshot(WORLD));
        assertTrue(service.snapshots().isEmpty());
        assertEquals(2, service.storedSeasons().size());
        assertEquals(123, service.bindWorld(WORLD).cycleTicks());
        service.unbindWorld(WORLD);
        service.tick(0, true);
        service.tick(500, true);
        assertNull(service.snapshot(WORLD));
        assertEquals(new StoredSeason(123, true), service.storedSeasons().get(WORLD));
        assertEquals(new StoredSeason(456, false), service.storedSeasons().get(OTHER));
        assertTrue(service.bindWorld(WORLD).paused());
    }

    @Test
    void unloadedWorldRebindDoesNotReplayConsumedTicks() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(0, true);
        service.tick(100, true);
        service.unbindWorld(WORLD);
        service.tick(1_000_000, true);
        service.bindWorld(WORLD);
        service.tick(1_000_010, true);
        assertEquals(110, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void restartUsesStoredCycleWithoutClockOrWallTimeDebt() {
        SeasonService first = service(true);
        first.bindWorld(WORLD);
        first.tick(100, true);
        first.tick(150, true);
        SeasonService second = new SeasonService(24_000, 8, 12, true, first.storedSeasons());
        second.bindWorld(WORLD);
        second.tick(Long.MAX_VALUE - 100, true);
        assertEquals(50, second.snapshot(WORLD).cycleTicks());
        second.tick(Long.MAX_VALUE - 99, true);
        assertEquals(51, second.snapshot(WORLD).cycleTicks());
    }

    @Test
    void savedInputAndPublishedMapsAreImmutableCopies() {
        Map<UUID, StoredSeason> saved = new HashMap<>();
        saved.put(WORLD, new StoredSeason(20, false));
        SeasonService service = new SeasonService(24_000, 8, 1, true, saved);
        saved.clear();
        service.bindWorld(WORLD);
        Map<UUID, SeasonSnapshot> previous = service.snapshots();
        Map<UUID, StoredSeason> stored = service.storedSeasons();
        assertThrows(UnsupportedOperationException.class, () -> previous.clear());
        assertThrows(UnsupportedOperationException.class, () -> stored.clear());
        service.tick(0, true);
        service.tick(10, true);
        assertEquals(20, previous.get(WORLD).cycleTicks());
        assertEquals(20, stored.get(WORLD).cycleTicks());
        assertEquals(30, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void settingSeasonResetsToBoundaryAndPreservesPause() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.setPaused(WORLD, true);
        SeasonSnapshot changed = service.setSeason(WORLD, SubSeason.LATE_WINTER);
        assertEquals(11 * SUB_TICKS, changed.cycleTicks());
        assertEquals(1, changed.dayOfSubSeason());
        assertTrue(changed.paused());
        assertEquals(2, changed.revision());
        assertSame(changed, service.setSeason(WORLD, SubSeason.LATE_WINTER));
        assertSame(changed, service.setPaused(WORLD, true));
    }

    @Test
    void bindingIsIdempotentAndUnknownUnbindIsSafe() {
        SeasonService service = service(true);
        SeasonSnapshot initial = service.bindWorld(WORLD);
        assertSame(initial, service.bindWorld(WORLD));
        service.unbindWorld(OTHER);
        assertSame(initial, service.snapshot(WORLD));
    }

    @Test
    void unboundMutationsFailRatherThanSilentlyCreateWorlds() {
        SeasonService service = service(true);
        assertThrows(IllegalArgumentException.class, () -> service.setSeason(WORLD, SubSeason.MID_SPRING));
        assertThrows(IllegalArgumentException.class, () -> service.setPaused(WORLD, true));
        assertTrue(service.storedSeasons().isEmpty());
    }

    @Test
    void reconfigureMigratesLoadedAndUnloadedPhasesAndResetsBaseline() {
        SeasonService service = new SeasonService(24_000, 8, 1, true,
                Map.of(WORLD, new StoredSeason(3 * SUB_TICKS + 17, true),
                        OTHER, new StoredSeason(7 * SUB_TICKS + 5, false)));
        service.bindWorld(WORLD);
        service.tick(100, true);
        service.reconfigure(12_000, 16, false);
        assertEquals(3 * SUB_TICKS + 17, service.snapshot(WORLD).cycleTicks());
        assertEquals(12_000, service.snapshot(WORLD).dayTicks());
        assertEquals(16, service.snapshot(WORLD).daysPerSubSeason());
        service.reconfigure(48_000, 8, false);
        assertEquals((3 * SUB_TICKS + 17) * 2, service.snapshot(WORLD).cycleTicks());
        assertEquals((7 * SUB_TICKS + 5) * 2, service.storedSeasons().get(OTHER).cycleTicks());
        assertTrue(service.snapshot(WORLD).paused());
        service.setPaused(WORLD, false);
        service.tick(1_000_000, true);
        assertEquals((3 * SUB_TICKS + 17) * 2, service.snapshot(WORLD).cycleTicks());
        service.tick(1_000_005, true);
        assertEquals((3 * SUB_TICKS + 17) * 2 + 5, service.snapshot(WORLD).cycleTicks());
        service.tick(1_000_100, false);
        assertEquals((3 * SUB_TICKS + 17) * 2 + 5, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void reconfigureUsesExactFloorWhenScaleProductOverflows() {
        int days = 350_000_000;
        long oldYear = 12L * Integer.MAX_VALUE * days;
        long cycle = oldYear - 3;
        SeasonService service = new SeasonService(Integer.MAX_VALUE, days, 1, true,
                Map.of(WORLD, new StoredSeason(cycle, false)));
        service.bindWorld(WORLD);
        long newYear = 12L * (Integer.MAX_VALUE - 1) * days;
        service.reconfigure(Integer.MAX_VALUE - 1, days, true);
        long expected = BigInteger.valueOf(cycle).multiply(BigInteger.valueOf(newYear))
                .divide(BigInteger.valueOf(oldYear)).longValueExact();
        assertEquals(expected, service.snapshot(WORLD).cycleTicks());
        assertTrue(expected < newYear);
    }

    @Test
    void invalidReconfigureLeavesStateAndBaselineUntouched() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(10, true);
        assertThrows(IllegalArgumentException.class, () -> service.reconfigure(0, 8, false));
        service.tick(20, true);
        assertEquals(10, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void sameSettingsStillResetClockBaseline() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(10, true);
        service.reconfigure(24_000, 8, true);
        service.tick(1_000_000, true);
        assertEquals(0, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void logicalWriterCanMoveBetweenThreadsWithSafeHandoff() throws InterruptedException {
        SeasonService service = service(true);
        SeasonSnapshot initial = service.bindWorld(WORLD);
        service.tick(0, true);
        AtomicReference<SeasonSnapshot> read = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            read.set(service.snapshot(WORLD));
            try {
                service.tick(10, true);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        thread.join(5_000);
        assertFalse(thread.isAlive());
        assertSame(initial, read.get());
        assertNull(failure.get());
        assertEquals(10, service.snapshot(WORLD).cycleTicks());
        service.tick(20, true);
        assertEquals(20, service.snapshot(WORLD).cycleTicks());
        assertEquals(0, initial.cycleTicks());
    }

    @Test
    void largeForwardClockJumpsAreNotClamped() {
        SeasonService service = service(true);
        service.bindWorld(WORLD);
        service.tick(0, true);
        service.tick(50_000, true);
        assertEquals(50_000, service.snapshot(WORLD).cycleTicks());
    }

    @Test
    void negativeSavedCycleIsNormalized() {
        SeasonService service = new SeasonService(24_000, 8, 1, true,
                Map.of(WORLD, new StoredSeason(-1, false)));
        assertEquals(12 * SUB_TICKS - 1, service.bindWorld(WORLD).cycleTicks());
    }

    @Test
    void invalidStartingStageAndNullInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonService(24_000, 8, -1, true, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SeasonService(24_000, 8, 13, true, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SeasonService(0, 8, 1, true, Map.of()));
        SeasonService service = service(true);
        assertThrows(NullPointerException.class, () -> service.bindWorld(null));
        assertThrows(NullPointerException.class, () -> service.snapshot(null));
    }
}
