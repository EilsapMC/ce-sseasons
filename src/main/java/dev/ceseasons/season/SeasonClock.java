package dev.ceseasons.season;

/** Overflow-safe arithmetic for the overworld clock; never consults wall time. */
public final class SeasonClock {
    public static final int DEFAULT_DAY_TICKS = 24_000;

    private SeasonClock() {
    }

    /**
     * Advances by the mathematical (not overflowing long) current-last delta.
     * A negative delta receives exactly one vanilla-day correction, even when
     * the result remains negative. Large forward jumps are not clamped.
     */
    public static long advance(long cycle, long current, long last, long yearTicks) {
        if (yearTicks <= 0) {
            throw new IllegalArgumentException("yearTicks must be positive");
        }
        long delta = Math.floorMod(current, yearTicks) - Math.floorMod(last, yearTicks);
        delta = Math.floorMod(delta, yearTicks);
        if (current < last) {
            delta = addModulo(delta, DEFAULT_DAY_TICKS % yearTicks, yearTicks);
        }
        return addModulo(Math.floorMod(cycle, yearTicks), delta, yearTicks);
    }

    // Both operands are already in [0, modulus). Avoid forming their sum.
    private static long addModulo(long left, long right, long modulus) {
        return left >= modulus - right ? left - (modulus - right) : left + right;
    }
}
