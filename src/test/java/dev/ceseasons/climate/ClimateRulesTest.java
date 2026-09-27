package dev.ceseasons.climate;

import dev.ceseasons.season.Season;
import dev.ceseasons.season.SeasonSnapshot;
import dev.ceseasons.season.SubSeason;
import dev.ceseasons.season.TropicalSeason;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClimateRulesTest {
    @Test
    void calendarOffsetsApplyOnlyToEligibleBiomes() {
        for (SubSeason subSeason : SubSeason.values()) {
            SeasonSnapshot snapshot = new SeasonSnapshot(UUID.randomUUID(), subSeason.ordinal() * 100L, 100, 1, false, 0);
            double offset = switch (subSeason) {
                case EARLY_SPRING, LATE_AUTUMN -> -0.25;
                case EARLY_WINTER, MID_WINTER, LATE_WINTER -> -0.8;
                default -> 0;
            };
            assertEquals(offset, snapshot.temperatureOffset());
            assertEquals(Math.clamp(0.8 + offset, -0.5, 2),
                    ClimateRules.temperature(0.8, snapshot.temperatureOffset(), false, false), 1E-12);
            assertEquals(0.81, ClimateRules.temperature(0.81, offset, false, false));
            assertEquals(0.8, ClimateRules.temperature(0.8, offset, true, false));
            assertEquals(0.8, ClimateRules.temperature(0.8, offset, false, true));
        }
    }

    @Test
    void clampsOnlyAffectedTemperaturesAndRejectsInvalidBase() {
        assertEquals(-0.5, ClimateRules.temperature(-0.4, -0.8, false, false));
        assertEquals(2, ClimateRules.temperature(0.8, 5, false, false));
        assertEquals(3, ClimateRules.temperature(3, -0.8, false, false));
        assertFalse(ClimateRules.affected(Double.NaN, false, false));
        assertFalse(ClimateRules.affected(Double.NEGATIVE_INFINITY, false, false));
        assertTrue(ClimateRules.affected(0.8, false, false));
        assertFalse(ClimateRules.affected(Math.nextUp(0.8), false, false));
    }

    @Test
    void altitudeIsPreservedButCannotMakeWarmBiomesEligible() {
        assertEquals(-0.1, ClimateRules.temperature(0.8, 0.7, -0.8, false, false), 1E-12);
        assertEquals(0.5, ClimateRules.temperature(1.2, 0.5, -0.8, false, false));
        assertEquals(0.7, ClimateRules.temperature(0.8, 0.7, -0.8, true, false));
        assertEquals(0.7, ClimateRules.temperature(0.8, 0.7, -0.8, false, true));
        assertFalse(ClimateRules.shouldThaw(ClimateRules.temperature(0.8, 0.1, 0, false, false), true));
    }

    @Test
    void snowThresholdIsStrictlyBelowPointFifteen() {
        assertTrue(ClimateRules.coldEnough(Math.nextDown(0.15)));
        assertFalse(ClimateRules.coldEnough(0.15));
        assertFalse(ClimateRules.coldEnough(Math.nextUp(0.15)));
    }

    @Test
    void shortensOnlyExcessiveClearTimersAndNeverAutumnOrStorms() {
        for (Season season : Season.values()) {
            int maximum = ClimateRules.maximumRainDelay(season);
            assertEquals(season == Season.AUTUMN ? 0 : season == Season.WINTER ? 36_000 : 96_000, maximum);
            assertFalse(ClimateRules.shortenRainDelay(season, true, false, maximum));
            assertFalse(ClimateRules.shortenRainDelay(season, true, false, 1));
            assertFalse(ClimateRules.shortenRainDelay(season, true, true, Integer.MAX_VALUE));
            assertFalse(ClimateRules.shortenRainDelay(season, false, false, Integer.MAX_VALUE));
            assertEquals(season != Season.AUTUMN,
                    ClimateRules.shortenRainDelay(season, true, false, maximum + 1));
        }
    }

    @Test
    void dryAndWetTropicsStillRespectWorldStorm() {
        for (TropicalSeason season : TropicalSeason.values()) {
            assertFalse(ClimateRules.precipitation(false, true, true, season));
            assertFalse(ClimateRules.precipitation(false, false, true, season));
            assertEquals(season != TropicalSeason.MID_DRY, ClimateRules.precipitation(true, true, true, season));
            assertEquals(season == TropicalSeason.MID_WET, ClimateRules.precipitation(true, false, true, season));
            assertTrue(ClimateRules.precipitation(true, true, false, season));
            assertFalse(ClimateRules.precipitation(true, false, false, season));
        }
    }

    @Test
    void freezeNeedsLowLightSourceWaterAndAnExposedEdge() {
        assertTrue(ClimateRules.canFreeze(0, 9, true, false));
        assertFalse(ClimateRules.canFreeze(0.15, 9, true, false));
        assertFalse(ClimateRules.canFreeze(0, 10, true, false));
        assertFalse(ClimateRules.canFreeze(0, 0, false, false));
        assertFalse(ClimateRules.canFreeze(0, 0, true, true));
    }

    @Test
    void snowNeedsPrecipitationSupportAndLowLight() {
        assertTrue(ClimateRules.canSnow(0, 9, true, true));
        assertFalse(ClimateRules.canSnow(0, 10, true, true));
        assertFalse(ClimateRules.canSnow(0.15, 0, true, true));
        assertFalse(ClimateRules.canSnow(0, 0, false, true));
        assertFalse(ClimateRules.canSnow(0, 0, true, false));
    }

    @Test
    void neverProtectsLightMeltingOrUnsupportedSnow() {
        assertTrue(ClimateRules.protectClimateFade(0.8, 0, true, false, true));
        assertFalse(ClimateRules.protectClimateFade(0.8, 0, true, true, true));
        assertFalse(ClimateRules.protectClimateFade(0.8, 0, true, false, false));
        assertFalse(ClimateRules.protectClimateFade(0.8, 0, false, false, true));
        assertFalse(ClimateRules.protectClimateFade(0, 0, true, false, true));
        assertFalse(ClimateRules.protectClimateFade(0.8, 0.15, true, false, true));
    }

    @Test
    void thawsOnlyEligibleWarmBiomes() {
        assertTrue(ClimateRules.shouldThaw(0.15, true));
        assertFalse(ClimateRules.shouldThaw(0.1499, true));
        assertFalse(ClimateRules.shouldThaw(1, false));
        assertFalse(ClimateRules.shouldThaw(Double.NaN, true));
        assertFalse(ClimateRules.shouldThaw(Double.POSITIVE_INFINITY, true));
    }
}
