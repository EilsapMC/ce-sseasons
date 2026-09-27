package dev.ceseasons.agriculture;

import dev.ceseasons.season.Season;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AgricultureSettingsTest {
    @Test void noCropListMeansNoManagedPlants() {
        var settings = AgricultureSettings.parse(new YamlConfiguration());
        assertTrue(settings.crops().isEmpty());
        assertEquals(6, settings.chance());
        assertEquals(48, settings.undergroundY());
        assertEquals(16, settings.greenhouseHeight());
        assertEquals(Set.of(
                "minecraft:glass",
                "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
                "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
                "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
                "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
                "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
                "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
                "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
                "minecraft:red_stained_glass", "minecraft:black_stained_glass"
        ), settings.greenhouseBlocks());
    }
    @Test void parsesOnlyConfiguredCropsAndClimateFallbacks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("agriculture.crops.minecraft:wheat", List.of("SPRING", "SUMMER"));
        config.set("agriculture.crops.ce_seasons:example_stem", List.of("summer"));
        config.set("climate.tropical-biomes", List.of("jungle"));
        config.set("climate.blacklisted-biomes", List.of("the_end"));
        var settings = AgricultureSettings.parse(config);
        assertEquals(2, settings.crops().size());
        assertEquals(Set.of(Season.SPRING, Season.SUMMER), settings.crops().get("minecraft:wheat"));
        assertEquals(Set.of("minecraft:jungle"), settings.tropicalBiomes());
        assertEquals(Set.of("minecraft:the_end"), settings.blacklistedBiomes());
    }
    @Test void editableLimitsAndCustomGreenhouseBlocks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("agriculture.mode", "wither");
        config.set("agriculture.out-of-season-chance", 8);
        config.set("agriculture.underground-y", 32);
        config.set("agriculture.greenhouse-height", 12);
        config.set("agriculture.greenhouse-blocks", List.of("ce_seasons:glass"));
        config.set("agriculture.unbreakable-crops", List.of("wheat"));
        var settings = AgricultureSettings.parse(config);
        assertEquals(FertilityRules.OffSeason.WITHER, settings.mode());
        assertEquals(8, settings.chance());
        assertEquals(32, settings.undergroundY());
        assertEquals(12, settings.greenhouseHeight());
        assertEquals(Set.of("ce_seasons:glass"), settings.greenhouseBlocks());
        assertTrue(settings.unbreakableCrops().contains("minecraft:wheat"));
    }
    @Test void validationRejectsBadModeChanceAndEmptySeasonLists() {
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("agriculture.mode", "fast");
        assertThrows(IllegalArgumentException.class, () -> AgricultureSettings.parse(bad));
        bad.set("agriculture.mode", "stop");
        bad.set("agriculture.out-of-season-chance", 0);
        assertThrows(IllegalArgumentException.class, () -> AgricultureSettings.parse(bad));
        bad.set("agriculture.out-of-season-chance", 6);
        bad.set("agriculture.crops.minecraft:wheat", List.of());
        assertThrows(IllegalArgumentException.class, () -> AgricultureSettings.parse(bad));
    }
}
