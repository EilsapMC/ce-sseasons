package dev.ceseasons.visual;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BiomeMappingsTest {
    private static BiomeMappings.Definition definition(String name, boolean tropical) {
        return new BiomeMappings.Definition("minecraft:" + name, true, tropical,
                IntStream.rangeClosed(1, 12).mapToObj(i -> "ceseasons:" + name + "/stage_%02d".formatted(i)).toList());
    }

    private static Map<String, Integer> ids(BiomeMappings.Definition definition) {
        Map<String, Integer> ids = new HashMap<>();
        ids.put(definition.base(), 7);
        for (int i = 0; i < 12; i++) ids.put(definition.variants().get(i), 100 + i);
        return ids;
    }

    @Test void resolvesActualNonContiguousIdsAndIsIdempotent() {
        var definition = definition("plains", false);
        var ids = ids(definition);
        var mappings = new BiomeMappings(200, List.of(definition), key -> ids.getOrDefault(key, -1), Set.of());
        for (int phase = 0; phase < 12; phase++) {
            assertEquals(100 + phase, mappings.remap(7, phase, 0));
            for (int previous = 100; previous < 112; previous++)
                assertEquals(100 + phase, mappings.remap(previous, phase, 0));
            assertEquals(100 + phase, mappings.remap(mappings.remap(7, phase, 0), phase, 0));
        }
        assertEquals(7, mappings.remap(104, -1, 0));
    }

    @Test void unknownAndThirdPartyIdsPassThrough() {
        var definition = definition("plains", false);
        var ids = ids(definition);
        var mappings = new BiomeMappings(200, List.of(definition), key -> ids.getOrDefault(key, -1), Set.of());
        for (int raw : new int[]{-3, 0, 8, 99, 112, 199, 200, 9999})
            assertEquals(raw, mappings.remap(raw, 4, 2));
    }

    @Test void tropicalUsesSixPairsInsteadOfTemperateOrdinal() {
        var definition = definition("jungle", true);
        var ids = ids(definition);
        var mappings = new BiomeMappings(200, List.of(definition), ids::get, Set.of());
        for (int phase = 0; phase < 6; phase++)
            assertEquals(100 + 2 * phase, mappings.remap(7, 11, phase));
    }

    @Test void blacklistedOwnVariantsRestoreBase() {
        var definition = definition("plains", false);
        var ids = ids(definition);
        var mappings = new BiomeMappings(200, List.of(definition), ids::get, Set.of("minecraft:plains"));
        assertEquals(7, mappings.remap(111, 6, 3));
        assertEquals(7, mappings.remap(7, 6, 3));
    }

    @Test void missingBootstrapVariantFailsRatherThanInventingId() {
        var definition = definition("plains", false);
        var ids = ids(definition);
        ids.remove(definition.variants().getLast());
        var error = assertThrows(IllegalStateException.class, () -> new BiomeMappings(200, List.of(definition),
                key -> ids.getOrDefault(key, -1), Set.of()));
        assertTrue(error.getMessage().contains("bootstrap"));
    }

    @Test void duplicateRegistryIdsFail() {
        var definition = definition("plains", false);
        assertThrows(IllegalStateException.class, () -> new BiomeMappings(200, List.of(definition), key -> 7, Set.of()));
    }

    @Test void packagedManifestContainsAllRealVariants() throws Exception {
        var definitions = BiomeMappings.read(getClass().getResourceAsStream("/season_datapack/biomes.index"));
        assertEquals(67, definitions.size());
        assertTrue(definitions.stream().anyMatch(d -> d.base().equals("minecraft:plains")));
        for (var definition : definitions) for (String variant : definition.variants()) {
            String path = "/season_datapack/data/ceseasons/worldgen/biome/" + variant.substring(10) + ".json";
            try (var resource = getClass().getResourceAsStream(path)) {
                assertNotNull(resource, path);
            }
        }
    }

    @Test void corruptOrEmptyIndexFails() {
        assertThrows(Exception.class, () -> BiomeMappings.read(null));
        assertThrows(Exception.class, () -> BiomeMappings.read(new ByteArrayInputStream(new byte[0])));
        assertThrows(Exception.class, () -> BiomeMappings.read(new ByteArrayInputStream(
                "minecraft:plains|maybe|false".getBytes(StandardCharsets.UTF_8))));
    }
}
