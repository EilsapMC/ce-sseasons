package dev.ceseasons.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeasonSnapshotTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long SUB_TICKS = 24_000L * 8;
    private static final long YEAR = SUB_TICKS * 12;

    private static SeasonSnapshot at(long ticks) {
        return new SeasonSnapshot(WORLD, ticks, 24_000, 8, false, 0);
    }

    @ParameterizedTest
    @CsvSource({
            "EARLY_SPRING,SPRING,MID_WET,9,-0.25",
            "MID_SPRING,SPRING,LATE_WET,1,0",
            "LATE_SPRING,SPRING,LATE_WET,9,0",
            "EARLY_SUMMER,SUMMER,EARLY_DRY,1,0",
            "MID_SUMMER,SUMMER,EARLY_DRY,9,0",
            "LATE_SUMMER,SUMMER,MID_DRY,1,0",
            "EARLY_AUTUMN,AUTUMN,MID_DRY,9,0",
            "MID_AUTUMN,AUTUMN,LATE_DRY,1,0",
            "LATE_AUTUMN,AUTUMN,LATE_DRY,9,-0.25",
            "EARLY_WINTER,WINTER,EARLY_WET,1,-0.8",
            "MID_WINTER,WINTER,EARLY_WET,9,-0.8",
            "LATE_WINTER,WINTER,MID_WET,1,-0.8"
    })
    void stageStartsHaveExactMappings(SubSeason sub, Season season, TropicalSeason tropical,
                                      long tropicalDay, double temperature) {
        SeasonSnapshot snapshot = at(sub.ordinal() * SUB_TICKS);
        assertAll(
                () -> assertEquals(sub, snapshot.subSeason()),
                () -> assertEquals(season, sub.season()),
                () -> assertEquals(season, snapshot.season()),
                () -> assertEquals(tropical, snapshot.tropicalSeason()),
                () -> assertEquals(1, snapshot.dayOfSubSeason()),
                () -> assertEquals(tropicalDay, snapshot.dayOfTropicalSeason()),
                () -> assertEquals(temperature, snapshot.temperatureOffset())
        );
    }

    @ParameterizedTest
    @EnumSource(SubSeason.class)
    void lastTickStaysInCurrentSubSeason(SubSeason stage) {
        SeasonSnapshot snapshot = at((stage.ordinal() + 1L) * SUB_TICKS - 1);
        assertEquals(stage, snapshot.subSeason());
        assertEquals(8, snapshot.dayOfSubSeason());
    }

    @ParameterizedTest
    @EnumSource(Season.class)
    void sensorsUseHalfOpenSeasons(Season season) {
        long start = season.ordinal() * 3 * SUB_TICKS;
        assertEquals(1, at(start).sensorPower(season));
        assertEquals(15, at(start + 3 * SUB_TICKS - 1).sensorPower(season));
        assertEquals(0, at(start - 1).sensorPower(season));
        assertEquals(0, at(start + 3 * SUB_TICKS).sensorPower(season));
        for (Season other : Season.values()) {
            if (other != season) {
                assertEquals(0, at(start).sensorPower(other));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"0,1", "38399,1", "38400,2", "288000,8",
            "537599,14", "537600,15", "575999,15", "576000,0"})
    void sensorRampsAtExactIntegerThresholds(long tick, int power) {
        assertEquals(power, at(tick).sensorPower(Season.SPRING));
    }

    @Test
    void tropicalDaysContinueAcrossYearBoundary() {
        assertEquals(TropicalSeason.MID_WET, at(YEAR - 1).tropicalSeason());
        assertEquals(8, at(YEAR - 1).dayOfTropicalSeason());
        assertEquals(TropicalSeason.MID_WET, at(YEAR).tropicalSeason());
        assertEquals(9, at(YEAR).dayOfTropicalSeason());
        assertEquals(16, at(SUB_TICKS - 1).dayOfTropicalSeason());
        assertEquals(1, at(SUB_TICKS).dayOfTropicalSeason());
    }

    @Test
    void wholeDaysAreOneBasedAndDoNotRoundUpPartialTicks() {
        assertEquals(1, at(23_999).dayOfSubSeason());
        assertEquals(2, at(24_000).dayOfSubSeason());
        assertEquals(8, at(SUB_TICKS - 1).dayOfSubSeason());
    }

    @Test
    void normalizesSignedAndVeryLargeCycles() {
        assertEquals(YEAR - 1, at(-1).cycleTicks());
        assertEquals(0, at(YEAR).cycleTicks());
        assertEquals(Math.floorMod(Long.MIN_VALUE, YEAR), at(Long.MIN_VALUE).cycleTicks());
        assertEquals(Math.floorMod(Long.MAX_VALUE, YEAR), at(Long.MAX_VALUE).cycleTicks());
        assertEquals(YEAR, at(0).yearTicks());
    }

    @Test
    void largeSensorProductDoesNotOverflow() {
        int days = 350_000_000;
        int dayTicks = Integer.MAX_VALUE;
        long duration = 3L * days * dayTicks;
        long cycle = duration - 1;
        SeasonSnapshot snapshot = new SeasonSnapshot(WORLD, cycle, dayTicks, days, false, 0);
        assertTrue(cycle > Long.MAX_VALUE / 15);
        assertEquals(15, snapshot.sensorPower(Season.SPRING));
        long half = duration / 2;
        int expected = BigInteger.valueOf(half).multiply(BigInteger.valueOf(15))
                .divide(BigInteger.valueOf(duration)).intValueExact() + 1;
        assertEquals(expected, new SeasonSnapshot(WORLD, half, dayTicks, days, false, 0)
                .sensorPower(Season.SPRING));
    }

    @Test
    void tropicalDayDoesNotOverflowForLargeDayCounts() {
        SeasonSnapshot snapshot = new SeasonSnapshot(WORLD, Integer.MAX_VALUE - 1L,
                1, Integer.MAX_VALUE, false, 0);
        assertEquals(2L * Integer.MAX_VALUE, snapshot.dayOfTropicalSeason());
    }

    @Test
    void rejectsInvalidCalendarAndRevision() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonSnapshot(WORLD, 0, 0, 8, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSnapshot(WORLD, 0, 1, -1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSnapshot(WORLD, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSnapshot(WORLD, 0, 1, 1, false, -1));
        assertThrows(NullPointerException.class, () -> new SeasonSnapshot(null, 0, 1, 1, false, 0));
        assertThrows(NullPointerException.class, () -> at(0).sensorPower(null));
    }

    @Test
    void smallestCalendarHasNoOffByOneAtYearEnd() {
        SeasonSnapshot snapshot = new SeasonSnapshot(WORLD, 11, 1, 1, true, 7);
        assertEquals(SubSeason.LATE_WINTER, snapshot.subSeason());
        assertEquals(1, snapshot.dayOfSubSeason());
        assertEquals(1, snapshot.dayOfTropicalSeason());
        assertEquals(12, snapshot.yearTicks());
        assertTrue(snapshot.paused());
        assertEquals(7, snapshot.revision());
    }
}
