package dev.ceseasons.platform;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftVersionTest {
    @Test void exactSupportedVersionsSelectDifferentDatapacks() {
        var roots = new HashSet<String>();
        for (MinecraftVersion version : MinecraftVersion.values()) {
            assertSame(version, MinecraftVersion.fromId(version.id()));
            assertTrue(roots.add(version.datapackRoot()));
        }
        assertEquals(3, roots.size());
    }

    @Test void unsupportedVersionsFailClosed() {
        for (String version : new String[] {"26.1", "26.1.1", "26.2-rc1", "26.4", "1.21.11", "", null}) {
            assertThrows(IllegalStateException.class, () -> MinecraftVersion.fromId(version));
        }
    }

    @Test void everyVersionIncludesItsOwnMetadataAndIndex() throws Exception {
        for (MinecraftVersion version : MinecraftVersion.values()) {
            String root = "/" + version.datapackRoot() + "/";
            try (var metadata = getClass().getResourceAsStream(root + "pack.mcmeta");
                 var index = getClass().getResourceAsStream(root + "biomes.index");
                 var manifest = getClass().getResourceAsStream(root + "manifest.json")) {
                assertNotNull(metadata, version.id());
                assertNotNull(index, version.id());
                assertNotNull(manifest, version.id());
                assertTrue(new String(manifest.readAllBytes(), StandardCharsets.UTF_8)
                        .contains("\"minecraft\": \"" + version.id() + "\""));
                long count = new String(index.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#")).count();
                assertEquals(switch (version) {
                    case V26_1_2 -> 65;
                    case V26_2 -> 66;
                    case V26_3 -> 67;
                }, count);
            }
        }
    }
}
