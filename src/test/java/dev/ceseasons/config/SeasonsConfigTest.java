package dev.ceseasons.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeasonsConfigTest {
    @Test
    void defaultsMatchCalendarContract() {
        var settings = SeasonsConfig.parse(new YamlConfiguration());
        assertEquals(24000, settings.dayTicks());
        assertEquals(8, settings.daysPerSubSeason());
        assertEquals(1, settings.startingSubSeason());
        assertTrue(settings.progressWhileEmpty());
        assertEquals(1200, settings.saveIntervalTicks());
    }

    @ParameterizedTest
    @ValueSource(strings = {"clock.day-ticks", "clock.days-per-sub-season",
            "storage.save-interval-seconds", "climate.interval-ticks",
            "visual.refresh-chunks-per-tick", "agriculture.greenhouse-height"})
    void rejectsZeroInsteadOfCreatingBrokenTasks(String path) {
        var config = new YamlConfiguration();
        config.set(path, 0);
        assertThrows(IllegalArgumentException.class, () -> SeasonsConfig.parse(config));
    }

    @Test
    void rejectsTruncatedDecimal() {
        var config = new YamlConfiguration();
        config.set("clock.day-ticks", 24000.25);
        assertThrows(IllegalArgumentException.class, () -> SeasonsConfig.parse(config));
    }

    @Test
    void rejectsNumericString() {
        var config = new YamlConfiguration();
        config.set("clock.day-ticks", "24000");
        assertThrows(IllegalArgumentException.class, () -> SeasonsConfig.parse(config));
    }

    @Test
    void rejectsInvalidStartingStage() {
        var config = new YamlConfiguration();
        config.set("clock.starting-sub-season", 13);
        assertThrows(IllegalArgumentException.class, () -> SeasonsConfig.parse(config));
    }

    @Test
    void keepsWorldSelectionImmutable() {
        var config = new YamlConfiguration();
        config.set("worlds.enabled", List.of("world"));
        var settings = SeasonsConfig.parse(config);
        config.set("worlds.enabled", List.of("other"));
        assertEquals(List.of("world"), settings.enabledWorlds());
        assertThrows(UnsupportedOperationException.class, () -> settings.enabledWorlds().add("x"));
    }
}
