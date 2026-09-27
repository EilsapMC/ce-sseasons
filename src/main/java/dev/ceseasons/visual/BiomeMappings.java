package dev.ceseasons.visual;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Immutable registry snapshot. Neither a Bukkit registry nor a world is consulted on Netty. */
public final class BiomeMappings {
    public record Definition(String base, boolean seasonal, boolean tropical, List<String> variants) {
        public Definition {
            variants = List.copyOf(variants);
            if (!base.startsWith("minecraft:") || variants.size() != 12) {
                throw new IllegalArgumentException("Expected vanilla base and twelve registered variants: " + base);
            }
            for (int i = 0; i < 12; i++) {
                String expected = "ceseasons:" + base.substring(10) + "/stage_%02d".formatted(i + 1);
                if (!variants.get(i).equals(expected)) throw new IllegalArgumentException("Unexpected variant key: " + variants.get(i));
            }
        }
    }

    private final int[] bases;
    private final int[][] variants;
    private final boolean[] tropical;
    private final boolean[] seasonal;

    public BiomeMappings(int size, List<Definition> definitions, ToIntFunction<String> resolver,
                         Set<String> blacklist) {
        if (size <= 0) throw new IllegalArgumentException("Empty server biome registry");
        bases = new int[size];
        Arrays.fill(bases, -1);
        variants = new int[size][];
        tropical = new boolean[size];
        seasonal = new boolean[size];
        Set<Integer> claimed = new HashSet<>();
        for (Definition definition : definitions) {
            int base = resolve(resolver, definition.base(), size);
            if (!claimed.add(base)) throw new IllegalStateException("Duplicate raw biome ID for " + definition.base());
            bases[base] = base;
            int[] stages = new int[12];
            for (int i = 0; i < stages.length; i++) {
                int id = resolve(resolver, definition.variants().get(i), size);
                if (!claimed.add(id)) throw new IllegalStateException("Duplicate variant raw ID " + id);
                stages[i] = id;
                bases[id] = base;
            }
            variants[base] = stages;
            tropical[base] = definition.tropical();
            seasonal[base] = definition.seasonal() && !blacklist.contains(definition.base());
        }
    }

    private static int resolve(ToIntFunction<String> resolver, String key, int size) {
        int id = resolver.applyAsInt(key);
        if (id < 0 || id >= size) {
            throw new IllegalStateException("Missing server biome " + key
                    + "; bootstrap datapack must load before registry freeze (no client-only append supported)");
        }
        return id;
    }

    public int size() { return bases.length; }

    /** stage is zero based; tropicalStage is one of six zero based dry/wet phases. */
    public int remap(int rawId, int stage, int tropicalStage) {
        if (rawId < 0 || rawId >= bases.length || bases[rawId] < 0) return rawId;
        int base = bases[rawId];
        if (!seasonal[base] || stage < 0) return base;
        if (stage >= 12 || tropicalStage < 0 || tropicalStage >= 6) {
            throw new IllegalArgumentException("Invalid season phase");
        }
        return variants[base][tropical[base] ? tropicalStage * 2 : stage];
    }

    public static List<Definition> read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Generated /season_datapack/biomes.index missing");
        List<Definition> result = new ArrayList<>();
        Set<String> bases = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length != 15 || !Set.of("true", "false").contains(fields[1])
                        || !Set.of("true", "false").contains(fields[2]) || !bases.add(fields[0])) {
                    throw new IOException("Invalid/duplicate generated biome index row: " + line);
                }
                result.add(new Definition(fields[0], Boolean.parseBoolean(fields[1]),
                        Boolean.parseBoolean(fields[2]), List.of(Arrays.copyOfRange(fields, 3, 15))));
            }
        }
        if (result.isEmpty()) throw new IOException("Empty generated biome index");
        return List.copyOf(result);
    }
}
