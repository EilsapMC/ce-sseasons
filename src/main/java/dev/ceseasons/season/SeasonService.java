package dev.ceseasons.season;

import dev.ceseasons.storage.SeasonStore;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure Java calendar service. Callers must serialize all mutating calls and
 * provide safe handoff between writer threads (for example, one scheduler).
 * A logical writer need not stay on the constructing or any fixed thread.
 * Readers on any thread see immutable maps and immutable snapshots. No method
 * uses wall time, and unloaded worlds remain in the persisted state map.
 */
public final class SeasonService {
    private final Map<UUID, SeasonSnapshot> states = new HashMap<>();
    private final Set<UUID> boundWorlds = new HashSet<>();
    private final int startingSubSeason;
    private Integer selectedStartingSubSeason;
    private int dayTicks;
    private int daysPerSubSeason;
    private boolean progressWhileEmpty;
    private boolean hasClockBaseline;
    private long lastClock;

    private volatile Map<UUID, SeasonSnapshot> published = Map.of();
    private volatile Map<UUID, SeasonStore.StoredSeason> publishedStored = Map.of();

    /** startingSubSeason is 0 for one startup draw, or 1..12 for a fixed stage. */
    public SeasonService(int dayTicks, int daysPerSubSeason, int startingSubSeason,
                         boolean progressWhileEmpty,
                         Map<UUID, SeasonStore.StoredSeason> saved) {
        SeasonSnapshot.checkedYearTicks(dayTicks, daysPerSubSeason);
        if (startingSubSeason < 0 || startingSubSeason > 12) {
            throw new IllegalArgumentException("startingSubSeason must be in 0..12");
        }
        this.dayTicks = dayTicks;
        this.daysPerSubSeason = daysPerSubSeason;
        this.startingSubSeason = startingSubSeason;
        this.progressWhileEmpty = progressWhileEmpty;
        Map.copyOf(Objects.requireNonNull(saved, "saved")).forEach((id, stored) ->
                states.put(id, new SeasonSnapshot(id, stored.cycleTicks(), dayTicks,
                        daysPerSubSeason, stored.paused(), 0)));
        publish();
    }

    public SeasonSnapshot bindWorld(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (boundWorlds.contains(worldId)) {
            return states.get(worldId);
        }
        SeasonSnapshot snapshot = states.get(worldId);
        if (snapshot == null) {
            if (selectedStartingSubSeason == null) {
                selectedStartingSubSeason = startingSubSeason == 0
                        ? ThreadLocalRandom.current().nextInt(12) : startingSubSeason - 1;
            }
            long cycle = selectedStartingSubSeason * ((long) dayTicks * daysPerSubSeason);
            snapshot = new SeasonSnapshot(worldId, cycle, dayTicks, daysPerSubSeason, false, 0);
            states.put(worldId, snapshot);
        }
        boundWorlds.add(worldId);
        publish();
        return snapshot;
    }

    public void unbindWorld(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (boundWorlds.remove(worldId)) {
            publish();
        }
    }

    /** The first sample establishes a baseline; pause/empty samples consume time. */
    public void tick(long overworldClock, boolean anyPlayerOnline) {
        if (!hasClockBaseline) {
            lastClock = overworldClock;
            hasClockBaseline = true;
            return;
        }
        long previous = lastClock;
        lastClock = overworldClock;
        if (!progressWhileEmpty && !anyPlayerOnline) {
            return;
        }
        Map<UUID, SeasonSnapshot> changes = new HashMap<>();
        for (UUID id : boundWorlds) {
            SeasonSnapshot old = states.get(id);
            if (old.paused()) {
                continue;
            }
            long cycle = SeasonClock.advance(old.cycleTicks(), overworldClock, previous, old.yearTicks());
            if (cycle != old.cycleTicks()) {
                changes.put(id, changed(old, cycle, old.paused(), dayTicks, daysPerSubSeason));
            }
        }
        if (!changes.isEmpty()) {
            states.putAll(changes);
            publish();
        }
    }

    /** Returns null for an unknown or unloaded world. */
    public SeasonSnapshot snapshot(UUID worldId) {
        return published.get(Objects.requireNonNull(worldId, "worldId"));
    }

    /** A stable immutable map of currently bound worlds. */
    public Map<UUID, SeasonSnapshot> snapshots() {
        return published;
    }

    /** Moves to the beginning of a subseason without changing paused state. */
    public SeasonSnapshot setSeason(UUID worldId, SubSeason subSeason) {
        Objects.requireNonNull(subSeason, "subSeason");
        SeasonSnapshot old = requireBound(worldId);
        long cycle = subSeason.ordinal() * ((long) dayTicks * daysPerSubSeason);
        if (cycle == old.cycleTicks()) {
            return old;
        }
        SeasonSnapshot next = changed(old, cycle, old.paused(), dayTicks, daysPerSubSeason);
        states.put(worldId, next);
        publish();
        return next;
    }

    public SeasonSnapshot setPaused(UUID worldId, boolean paused) {
        SeasonSnapshot old = requireBound(worldId);
        if (old.paused() == paused) {
            return old;
        }
        SeasonSnapshot next = changed(old, old.cycleTicks(), paused, dayTicks, daysPerSubSeason);
        states.put(worldId, next);
        publish();
        return next;
    }

    /** Includes saved worlds that have never been bound, and worlds now unloaded. */
    public Map<UUID, SeasonStore.StoredSeason> storedSeasons() {
        return publishedStored;
    }

    /**
     * Preserves each world's fraction of the year, rounded down to a tick.
     * Migrates unloaded worlds as well, and resets the clock sample baseline.
     */
    public void reconfigure(int dayTicks, int daysPerSubSeason, boolean progressWhileEmpty) {
        long newYear = SeasonSnapshot.checkedYearTicks(dayTicks, daysPerSubSeason);
        Map<UUID, SeasonSnapshot> replacements = new HashMap<>();
        if (this.dayTicks != dayTicks || this.daysPerSubSeason != daysPerSubSeason) {
            BigInteger targetYear = BigInteger.valueOf(newYear);
            for (SeasonSnapshot old : states.values()) {
                long cycle = BigInteger.valueOf(old.cycleTicks()).multiply(targetYear)
                        .divide(BigInteger.valueOf(old.yearTicks())).longValueExact();
                replacements.put(old.worldId(), changed(old, cycle, old.paused(), dayTicks, daysPerSubSeason));
            }
        }
        states.putAll(replacements);
        this.dayTicks = dayTicks;
        this.daysPerSubSeason = daysPerSubSeason;
        this.progressWhileEmpty = progressWhileEmpty;
        hasClockBaseline = false;
        if (!replacements.isEmpty()) {
            publish();
        }
    }

    private SeasonSnapshot requireBound(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (!boundWorlds.contains(worldId)) {
            throw new IllegalArgumentException("World is not bound: " + worldId);
        }
        return states.get(worldId);
    }

    private static SeasonSnapshot changed(SeasonSnapshot old, long cycle, boolean paused,
                                          int dayTicks, int daysPerSubSeason) {
        return new SeasonSnapshot(old.worldId(), cycle, dayTicks, daysPerSubSeason, paused,
                Math.incrementExact(old.revision()));
    }

    private void publish() {
        Map<UUID, SeasonSnapshot> loaded = new HashMap<>();
        boundWorlds.forEach(id -> loaded.put(id, states.get(id)));
        Map<UUID, SeasonStore.StoredSeason> stored = new HashMap<>();
        states.forEach((id, snapshot) -> stored.put(id,
                new SeasonStore.StoredSeason(snapshot.cycleTicks(), snapshot.paused())));
        publishedStored = Map.copyOf(stored);
        published = Map.copyOf(loaded);
    }

}
