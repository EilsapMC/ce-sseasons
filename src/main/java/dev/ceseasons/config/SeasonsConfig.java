package dev.ceseasons.config;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

public record SeasonsConfig(int dayTicks, int daysPerSubSeason, int startingSubSeason,
                            boolean progressWhileEmpty, String sourceWorld,
                            List<String> enabledWorlds, long saveIntervalTicks) {
    public SeasonsConfig {
        enabledWorlds = List.copyOf(enabledWorlds);
    }

    public static SeasonsConfig parse(FileConfiguration config) {
        int dayTicks = integer(config, "clock.day-ticks", 24000, 1, 10000000);
        int days = integer(config, "clock.days-per-sub-season", 8, 1, 100000);
        int starting = integer(config, "clock.starting-sub-season", 1, 0, 12);
        int interval = integer(config, "storage.save-interval-seconds", 60, 1, 86400);
        integer(config, "climate.interval-ticks", 20, 1, 12000);
        integer(config, "climate.samples-per-player", 2, 1, 16);
        integer(config, "climate.max-samples-per-run", 64, 1, 4096);
        integer(config, "climate.radius-chunks", 3, 1, 16);
        integer(config, "visual.refresh-chunks-per-tick", 2, 1, 64);
        integer(config, "visual.refresh-interval-ticks", 5, 1, 1200);
        integer(config, "agriculture.out-of-season-chance", 6, 1, 1000000);
        integer(config, "agriculture.greenhouse-height", 16, 1, 64);
        Object progress = config.get("clock.progress-while-empty", true);
        if (!(progress instanceof Boolean enabled)) {
            throw new IllegalArgumentException("clock.progress-while-empty must be a boolean");
        }
        Object source = config.get("clock.source-world", "");
        if (!(source instanceof String sourceWorld)) {
            throw new IllegalArgumentException("clock.source-world must be a world name or UUID string");
        }
        Object worlds = config.get("worlds.enabled", List.of());
        if (!(worlds instanceof List<?> list) || list.stream().anyMatch(
                item -> !(item instanceof String name) || name.isBlank())) {
            throw new IllegalArgumentException("worlds.enabled must be a list of nonblank world names/UUIDs");
        }
        return new SeasonsConfig(dayTicks, days, starting, enabled, sourceWorld,
                list.stream().map(String.class::cast).toList(), interval * 20L);
    }

    private static int integer(FileConfiguration config, String path, int fallback, int minimum, int maximum) {
        Object raw = config.get(path);
        if (raw != null && !(raw instanceof Number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        int value = config.getInt(path, fallback);
        if (value < minimum || value > maximum || raw instanceof Number number
                && number.doubleValue() != value) {
            throw new IllegalArgumentException(path + " must be in " + minimum + ".." + maximum);
        }
        return value;
    }
}
