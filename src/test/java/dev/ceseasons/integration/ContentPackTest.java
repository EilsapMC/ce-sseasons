package dev.ceseasons.integration;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentPackTest {
    private static final Path PACK = Path.of("content-pack/ce_seasons");
    private static final Path ASSETS = PACK.resolve("resourcepack/assets/ce_seasons");

    private String texturePixels(String relative) throws IOException {
        Path path = ASSETS.resolve("textures/" + relative + ".png");
        var image = ImageIO.read(path.toFile());
        assertNotNull(image, "Texture must be a decodable PNG: " + path);
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
        return Arrays.toString(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }

    @Test void allEighteenCalendarPhasesAndUnknownHaveAssets() throws IOException {
        Set<String> distinct = new HashSet<>();
        for (int index = -1; index < 18; index++) {
            String phase = index == -1 ? "unknown" : "stage_" + index;
            String texture = index == -1 ? "calendar_null" : index < 12
                    ? String.format(Locale.ROOT, "calendar_%02d", index)
                    : String.format(Locale.ROOT, "calendar_tropical_%02d", index - 12);
            String model = Files.readString(ASSETS.resolve("models/item/calendar/" + phase + ".json"));
            assertTrue(model.contains("\"minecraft:item/generated\""));
            assertTrue(model.contains("\"ce_seasons:item/" + texture + "\""));
            assertTrue(distinct.add(texturePixels("item/" + texture)), "Phases must have visibly distinct textures");
            assertTrue(Files.readString(ASSETS.resolve("items/calendar/" + phase + ".json")).contains("ce_seasons:item/calendar/" + phase));
        }
        assertEquals(19, distinct.size());
    }
    @Test void allFourSensorModesHaveDistinctOriginalTextures() throws IOException {
        Set<String> textures = new HashSet<>();
        texturePixels("block/season_sensor_side");
        for (String mode : List.of("spring", "summer", "autumn", "winter")) {
            String model = Files.readString(ASSETS.resolve("models/block/season_sensor/" + mode + ".json"));
            assertTrue(model.contains("\"minecraft:block/template_daylight_detector\""));
            assertTrue(model.contains("\"ce_seasons:block/season_sensor_" + mode + "_top\""));
            assertTrue(model.contains("\"ce_seasons:block/season_sensor_side\""));
            assertTrue(textures.add(texturePixels("block/season_sensor_" + mode + "_top")));
        }
        String config = Files.readString(PACK.resolve("configuration/seasons.yml"));
        assertTrue(config.contains("type: ce_seasons:season_sensor"));
        assertTrue(config.contains("max: 15"));
        for (int mode = 0; mode < 4; mode++) assertTrue(config.contains("mode=" + mode + ":"));
        assertTrue(config.contains("recipes:"));
    }
    @Test void cropExamplesReplaceOriginalFactoryRatherThanChainAGate() throws IOException {
        String config = Files.readString(PACK.resolve("configuration/agriculture_examples.yml"));
        assertTrue(config.contains("type: ce_seasons:season_crop"));
        assertTrue(config.contains("type: ce_seasons:season_stem"));
        assertFalse(config.contains("- type: crop_block"));
        assertFalse(config.contains("- type: stem_block"));
        assertTrue(config.contains("type: attached_stem_block"));
    }
}
