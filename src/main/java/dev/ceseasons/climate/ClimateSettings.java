package dev.ceseasons.climate;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record ClimateSettings(boolean enabled, boolean weather, boolean snowAndIce, int intervalTicks,
                       int samplesPerPlayer, int maxSamplesPerRun, int radiusChunks,
                       Set<String> tropicalBiomes, Set<String> blacklistedBiomes) {
    static ClimateSettings read(FileConfiguration config) {
        if (config.contains("climate") && !config.isConfigurationSection("climate")) {
            throw new IllegalArgumentException("climate must be a configuration section");
        }
        return new ClimateSettings(bool(config, "enabled"), bool(config, "weather"),
                bool(config, "snow-and-ice"), integer(config, "interval-ticks", 20, 12_000),
                integer(config, "samples-per-player", 2, 16),
                integer(config, "max-samples-per-run", 64, 4096),
                integer(config, "radius-chunks", 3, 16),
                biomes(config, "tropical-biomes"), biomes(config, "blacklisted-biomes"));
    }

    private static boolean bool(FileConfiguration config, String key) {
        Object value = config.get("climate." + key);
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("climate." + key + " must be a boolean");
    }

    private static int integer(FileConfiguration config, String key, int fallback, int maximum) {
        Object value = config.get("climate." + key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            int result = number.intValue();
            if (result >= 1 && result <= maximum && number.doubleValue() == result) {
                return result;
            }
        }
        throw new IllegalArgumentException("climate." + key + " must be an integer in 1.." + maximum);
    }

    private static Set<String> biomes(FileConfiguration config, String key) {
        Object value = config.get("climate." + key);
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("climate." + key + " must be a string list");
        }
        Set<String> result = new HashSet<>();
        for (Object element : list) {
            if (!(element instanceof String text)) {
                throw new IllegalArgumentException("climate." + key + " must contain only biome keys");
            }
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (!normalized.contains(":")) {
                normalized = "minecraft:" + normalized;
            }
            if (!normalized.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("Invalid biome key in climate." + key + ": " + text);
            }
            result.add(normalized);
        }
        return Set.copyOf(result);
    }
}
