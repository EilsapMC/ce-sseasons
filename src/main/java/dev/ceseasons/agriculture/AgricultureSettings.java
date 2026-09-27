package dev.ceseasons.agriculture;

import dev.ceseasons.season.Season;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public record AgricultureSettings(boolean enabled, FertilityRules.OffSeason mode,
        Map<String, Set<Season>> crops, Set<String> excludedWorlds, Set<String> tropicalBiomes,
        Set<String> blacklistedBiomes, Set<String> infertileBiomes, int chance, int undergroundY,
        int greenhouseHeight, Set<String> greenhouseBlocks, Set<String> unbreakableCrops) {
    public static AgricultureSettings parse(FileConfiguration config) {
        String value = config.getString("agriculture.mode", "slow");
        FertilityRules.OffSeason mode = FertilityRules.OffSeason.valueOf(value.toUpperCase(Locale.ROOT));
        int chance = config.getInt("agriculture.out-of-season-chance", 6);
        int undergroundY = config.getInt("agriculture.underground-y", 48);
        int greenhouseHeight = config.getInt("agriculture.greenhouse-height", 16);
        if (chance < 1 || chance > 1000000 || greenhouseHeight < 1 || greenhouseHeight > 256)
            throw new IllegalArgumentException("Invalid agriculture chance or greenhouse height");
        Set<String> glass = new HashSet<>(keys(config, "agriculture.greenhouse-blocks", "agriculture.greenhouse-blocks"));
        if (!config.contains("agriculture.greenhouse-blocks")) {
            glass.add("minecraft:glass");
            for (org.bukkit.Material material : org.bukkit.Material.values())
                if (!material.isLegacy() && material.name().endsWith("_STAINED_GLASS")) glass.add(material.getKey().toString());
        }
        Map<String, Set<Season>> crops = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("agriculture.crops");
        if (section != null) for (String id : section.getKeys(false)) {
            String key = key(id);
            EnumSet<Season> seasons = EnumSet.noneOf(Season.class);
            for (String season : section.getStringList(id)) seasons.add(Season.valueOf(season.toUpperCase(Locale.ROOT)));
            if (seasons.isEmpty()) throw new IllegalArgumentException("Empty crop seasons: " + id);
            crops.put(key, Set.copyOf(seasons));
        }
        return new AgricultureSettings(config.getBoolean("agriculture.enabled", true), mode, Map.copyOf(crops),
                Set.copyOf(config.getStringList("agriculture.excluded-worlds")),
                keys(config, "agriculture.tropical-biomes", "climate.tropical-biomes"),
                keys(config, "agriculture.blacklisted-biomes", "climate.blacklisted-biomes"),
                keys(config, "agriculture.infertile-biomes", "agriculture.infertile-biomes"), chance, undergroundY, greenhouseHeight, Set.copyOf(glass),
                keys(config, "agriculture.unbreakable-crops", "agriculture.unbreakable-crops"));
    }
    public static String key(String input) {
        String id = input.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid crop/biome key: " + input);
        return id;
    }
    private static Set<String> keys(FileConfiguration c, String path, String fallback) {
        Set<String> result = new HashSet<>();
        for (String id : c.getStringList(c.contains(path) ? path : fallback)) result.add(key(id));
        return Set.copyOf(result);
    }
}
