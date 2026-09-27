package dev.ceseasons.season;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** An immutable, safely publishable view of one world's season calendar. */
public record SeasonSnapshot(UUID worldId, long cycleTicks, int dayTicks,
                             int daysPerSubSeason, boolean paused, long revision) {
    public static final int DEFAULT_DAY_TICKS = SeasonClock.DEFAULT_DAY_TICKS;
    public static final int DEFAULT_DAYS_PER_SUB_SEASON = 8;

    private static final SubSeason[] SUB_SEASONS = SubSeason.values();
    private static final TropicalSeason[] TROPICAL_BY_SUB_SEASON = {
            TropicalSeason.MID_WET, TropicalSeason.LATE_WET, TropicalSeason.LATE_WET,
            TropicalSeason.EARLY_DRY, TropicalSeason.EARLY_DRY, TropicalSeason.MID_DRY,
            TropicalSeason.MID_DRY, TropicalSeason.LATE_DRY, TropicalSeason.LATE_DRY,
            TropicalSeason.EARLY_WET, TropicalSeason.EARLY_WET, TropicalSeason.MID_WET
    };

    public SeasonSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        long year = checkedYearTicks(dayTicks, daysPerSubSeason);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        cycleTicks = Math.floorMod(cycleTicks, year);
    }

    public SubSeason subSeason() {
        return SUB_SEASONS[(int) (cycleTicks / subSeasonTicks())];
    }

    public Season season() {
        return subSeason().season();
    }

    public TropicalSeason tropicalSeason() {
        return TROPICAL_BY_SUB_SEASON[(int) (cycleTicks / subSeasonTicks())];
    }

    /** One-based day within the current temperate subseason. */
    public int dayOfSubSeason() {
        return (int) ((cycleTicks / dayTicks) % daysPerSubSeason) + 1;
    }

    /**
     * One-based day within a tropical stage (twice a temperate subseason).
     * MID_WET starts in late winter, so early spring continues at day d+1.
     * A long result also supports calendars with more than Integer.MAX_VALUE
     * days in a tropical stage.
     */
    public long dayOfTropicalSeason() {
        return ((cycleTicks / dayTicks + daysPerSubSeason)
                % (2L * daysPerSubSeason)) + 1;
    }

    /** Zero outside the half-open season; otherwise floor(min(15*p+1, 15)). */
    public int sensorPower(Season requestedSeason) {
        Objects.requireNonNull(requestedSeason, "requestedSeason");
        if (season() != requestedSeason) {
            return 0;
        }
        long duration = 3L * subSeasonTicks();
        long elapsed = cycleTicks % duration;
        long ramp = elapsed <= Long.MAX_VALUE / 15
                ? elapsed * 15 / duration
                : BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(15))
                        .divide(BigInteger.valueOf(duration)).longValueExact();
        return (int) Math.min(15, ramp + 1);
    }

    public long yearTicks() {
        return 12L * subSeasonTicks();
    }

    /** Calendar offset only; biome eligibility is the climate layer's concern. */
    public double temperatureOffset() {
        return switch (subSeason()) {
            case EARLY_SPRING, LATE_AUTUMN -> -0.25;
            case EARLY_WINTER, MID_WINTER, LATE_WINTER -> -0.8;
            default -> 0.0;
        };
    }

    private long subSeasonTicks() {
        return (long) dayTicks * daysPerSubSeason;
    }

    static long checkedYearTicks(int dayTicks, int daysPerSubSeason) {
        if (dayTicks <= 0 || daysPerSubSeason <= 0) {
            throw new IllegalArgumentException("dayTicks and daysPerSubSeason must be positive");
        }
        try {
            return Math.multiplyExact(Math.multiplyExact((long) dayTicks, daysPerSubSeason), 12L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("season year exceeds long tick range", overflow);
        }
    }
}
