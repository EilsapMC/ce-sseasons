package dev.ceseasons.climate;

import dev.ceseasons.season.Season;
import dev.ceseasons.season.TropicalSeason;

/** Pure climate policy, independent of Bukkit and scheduler ownership. */
public final class ClimateRules {
    public static final double SNOW_THRESHOLD = 0.15;
    public static final double MAX_AFFECTED_BASE_TEMPERATURE = 0.8;
    public static final int MIN_RAIN_DELAY = 12_000;

    private ClimateRules() {
    }

    public static boolean affected(double baseTemperature, boolean tropical, boolean blacklisted) {
        return Double.isFinite(baseTemperature) && baseTemperature <= MAX_AFFECTED_BASE_TEMPERATURE
                && !tropical && !blacklisted;
    }

    public static double temperature(double baseTemperature, double offset,
                                     boolean tropical, boolean blacklisted) {
        return temperature(baseTemperature, baseTemperature, offset, tropical, blacklisted);
    }

    /** Gate by raw biome temperature, but retain vanilla altitude/noise modifiers. */
    public static double temperature(double baseTemperature, double localTemperature, double offset,
                                     boolean tropical, boolean blacklisted) {
        if (!affected(baseTemperature, tropical, blacklisted)) {
            return localTemperature;
        }
        return Math.clamp(localTemperature + offset, -0.5, 2.0);
    }

    public static boolean coldEnough(double temperature) {
        return temperature < SNOW_THRESHOLD;
    }

    /** Zero means leave the vanilla timer untouched, including late autumn. */
    public static int maximumRainDelay(Season season) {
        return switch (season) {
            case WINTER -> 36_000;
            case SPRING, SUMMER -> 96_000;
            case AUTUMN -> 0;
        };
    }

    public static boolean shortenRainDelay(Season season, boolean advanceWeather,
                                            boolean storm, int remainingTicks) {
        int maximum = maximumRainDelay(season);
        return advanceWeather && !storm && maximum != 0 && remainingTicks > maximum;
    }

    /** Tropical wet seasons do not invent a storm in a clear world. */
    public static boolean precipitation(boolean storm, boolean vanillaPrecipitation,
                                         boolean tropical, TropicalSeason season) {
        if (!storm) {
            return false;
        }
        if (!tropical) {
            return vanillaPrecipitation;
        }
        return switch (season) {
            case MID_DRY -> false;
            case MID_WET -> true;
            default -> vanillaPrecipitation;
        };
    }

    public static boolean canFreeze(double temperature, int blockLight,
                                    boolean sourceWater, boolean waterOnEverySide) {
        return coldEnough(temperature) && blockLight < 10 && sourceWater && !waterOnEverySide;
    }

    public static boolean canSnow(double temperature, int blockLight,
                                  boolean precipitation, boolean supported) {
        return coldEnough(temperature) && blockLight < 10 && precipitation && supported;
    }

    /** Never suppress a light-driven fade merely because the calendar says winter. */
    public static boolean protectClimateFade(double baseTemperature, double temperature,
                                             boolean affected, boolean lightDriven, boolean supported) {
        return affected && !coldEnough(baseTemperature) && coldEnough(temperature)
                && !lightDriven && supported;
    }

    public static boolean shouldThaw(double temperature, boolean affected) {
        return affected && Double.isFinite(temperature) && !coldEnough(temperature);
    }
}
