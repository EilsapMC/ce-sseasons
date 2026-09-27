package dev.ceseasons.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SeasonClockTest {
    @ParameterizedTest
    @CsvSource({
            "0,100,100,2304000,0",
            "0,101,100,2304000,1",
            "0,0,23999,2304000,1",
            "100,100,1000,2304000,23200",
            "100,0,48000,2304000,2280100",
            "0,50000,0,2304000,50000",
            "10,4613000,0,2304000,5010",
            "2303999,2,1,2304000,0",
            "-1,0,0,2304000,2303999",
            "100,100000,0,1,0"
    })
    void exactClockExamples(long cycle, long current, long last, long year, long expected) {
        assertEquals(expected, SeasonClock.advance(cycle, current, last, year));
    }

    @Test
    void mathematicalLongDeltaCanExceedSignedLongRange() {
        assertEquals(reference(91, Long.MAX_VALUE, Long.MIN_VALUE, 2_304_000),
                SeasonClock.advance(91, Long.MAX_VALUE, Long.MIN_VALUE, 2_304_000));
        assertEquals(reference(91, Long.MIN_VALUE, Long.MAX_VALUE, 2_304_000),
                SeasonClock.advance(91, Long.MIN_VALUE, Long.MAX_VALUE, 2_304_000));
    }

    @Test
    void modularAdditionIsSafeAtMaximumYear() {
        assertEquals(reference(Long.MAX_VALUE - 2, Long.MAX_VALUE, -1, Long.MAX_VALUE),
                SeasonClock.advance(Long.MAX_VALUE - 2, Long.MAX_VALUE, -1, Long.MAX_VALUE));
        assertEquals(reference(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                SeasonClock.advance(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void correctionIsExactlyOneVanillaDayEvenWithCustomCalendar() {
        assertEquals(Math.floorMod(-50_000L + 24_000, 1_200),
                SeasonClock.advance(0, 0, 50_000, 1_200));
    }

    @Test
    void randomizedClockMatchesUnboundedIntegerOracle() {
        Random random = new Random(0x5EA50L);
        for (int index = 0; index < 10_000; index++) {
            long year = random.nextLong() & Long.MAX_VALUE;
            if (year == 0) {
                year = 1;
            }
            long cycle = random.nextLong();
            long current = random.nextLong();
            long last = random.nextLong();
            assertEquals(reference(cycle, current, last, year),
                    SeasonClock.advance(cycle, current, last, year), "sample " + index);
        }
    }

    @Test
    void rejectsNonPositiveYear() {
        assertThrows(IllegalArgumentException.class, () -> SeasonClock.advance(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SeasonClock.advance(0, 0, 0, -1));
    }

    private static long reference(long cycle, long current, long last, long year) {
        BigInteger difference = BigInteger.valueOf(current).subtract(BigInteger.valueOf(last));
        if (difference.signum() < 0) {
            difference = difference.add(BigInteger.valueOf(24_000));
        }
        return BigInteger.valueOf(cycle).add(difference).mod(BigInteger.valueOf(year)).longValueExact();
    }
}
