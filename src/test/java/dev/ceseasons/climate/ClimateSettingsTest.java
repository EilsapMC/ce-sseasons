package dev.ceseasons.climate;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClimateSettingsTest {
    @Test
    void readsExistingDefaultsWithoutMutatingConfiguration() {
        YamlConfiguration config = new YamlConfiguration();
        ClimateSettings settings = ClimateSettings.read(config);
        assertTrue(settings.enabled());
        assertTrue(settings.weather());
        assertTrue(settings.snowAndIce());
        assertEquals(20, settings.intervalTicks());
        assertEquals(2, settings.samplesPerPlayer());
        assertEquals(64, settings.maxSamplesPerRun());
        assertEquals(3, settings.radiusChunks());
        assertTrue(config.getKeys(true).isEmpty());
    }

    @Test
    void readsFlagsAndConfiguredBiomeKeys() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("climate.enabled", false);
        config.set("climate.weather", false);
        config.set("climate.snow-and-ice", false);
        config.set("climate.tropical-biomes", List.of("minecraft:jungle", "SAVANNA", "custom:wet/forest"));
        config.set("climate.blacklisted-biomes", List.of("minecraft:deep_dark"));
        ClimateSettings settings = ClimateSettings.read(config);
        assertFalse(settings.enabled());
        assertFalse(settings.weather());
        assertFalse(settings.snowAndIce());
        assertEquals(Set.of("minecraft:jungle", "minecraft:savanna", "custom:wet/forest"), settings.tropicalBiomes());
        assertEquals(Set.of("minecraft:deep_dark"), settings.blacklistedBiomes());
        assertThrows(UnsupportedOperationException.class, () -> settings.tropicalBiomes().add("minecraft:desert"));
    }

    @Test
    void rejectsUnknownValueShapesBeforeReload() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("climate", "enabled");
        assertThrows(IllegalArgumentException.class, () -> ClimateSettings.read(config));
        config.set("climate", null);
        for (String flag : List.of("enabled", "weather", "snow-and-ice")) {
            config.set("climate." + flag, "true");
            assertThrows(IllegalArgumentException.class, () -> ClimateSettings.read(config));
            config.set("climate." + flag, null);
        }
        for (String list : List.of("tropical-biomes", "blacklisted-biomes")) {
            for (Object invalid : List.of("minecraft:jungle", List.of(1), List.of("bad namespace:forest"), List.of(""))) {
                config.set("climate." + list, invalid);
                assertThrows(IllegalArgumentException.class, () -> ClimateSettings.read(config));
            }
            config.set("climate." + list, null);
        }
    }

    @Test
    void validatesAllNumericLimitsEvenWhenDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("climate.enabled", false);
        String[] keys = {"interval-ticks", "samples-per-player", "max-samples-per-run", "radius-chunks"};
        int[] maximums = {12_000, 16, 4096, 16};
        for (int i = 0; i < keys.length; i++) {
            String key = "climate." + keys[i];
            for (Object invalid : List.of(0, -1, 1.5, "2", maximums[i] + 1, Double.NaN)) {
                config.set(key, invalid);
                assertThrows(IllegalArgumentException.class, () -> ClimateSettings.read(config), key);
            }
            config.set(key, maximums[i]);
            assertDoesNotThrow(() -> ClimateSettings.read(config));
            config.set(key, null);
        }
    }
}
