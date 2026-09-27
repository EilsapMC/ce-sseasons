package dev.ceseasons.platform.v26_3;

import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline integration main; supply a real vanilla server jar plus its bundled libraries. */
public final class NativeBiomeDatapackProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: NativeBiomeDatapackProbe <datapack-root>");
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        Object registries = Class.forName("net.minecraft.data.registries.VanillaRegistries")
                .getMethod("createLookup").invoke(null);
        Class<?> opsType = Class.forName("com.mojang.serialization.DynamicOps");
        Object jsonOps = Class.forName("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
        Object ops = Class.forName("net.minecraft.resources.RegistryOps").getMethod("create", opsType,
                Class.forName("net.minecraft.core.HolderLookup$Provider")).invoke(null, jsonOps, registries);
        Object codec = Class.forName("net.minecraft.world.level.biome.Biome").getField("DIRECT_CODEC").get(null);
        Method parse = Class.forName("com.mojang.serialization.Codec").getMethod("parse", opsType, Object.class);
        Method getOrThrow = Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow");
        Method parseJson = Class.forName("com.google.gson.JsonParser").getMethod("parseReader", Reader.class);
        Class<?> jsonElement = Class.forName("com.google.gson.JsonElement");
        Class<?> jsonObject = Class.forName("com.google.gson.JsonObject");
        Method asObject = jsonElement.getMethod("getAsJsonObject");
        Method getObject = jsonObject.getMethod("getAsJsonObject", String.class);
        Method addColor = jsonObject.getMethod("addProperty", String.class, String.class);
        Method contains = Class.forName("net.minecraft.world.attribute.EnvironmentAttributeMap").getMethod("contains",
                Class.forName("net.minecraft.world.attribute.EnvironmentAttribute"));
        Class<?> attributesType = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
        Method attributes = Class.forName("net.minecraft.world.level.biome.Biome").getMethod("getAttributes");
        List<Path> files;
        try (var paths = Files.walk(Path.of(args[0]).resolve("data"))) {
            files = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/worldgen/biome/")).sorted().toList();
        }
        if (files.isEmpty()) throw new AssertionError("No biome files found");
        int decoded = 0;
        int withAtmosphere = 0;
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Object json = parseJson.invoke(null, reader);
                Object originalBiome = getOrThrow.invoke(parse.invoke(codec, ops, json));
                Object effectsJson = getObject.invoke(asObject.invoke(json), "effects");
                Object effects = originalBiome.getClass().getMethod("getSpecialEffects").invoke(originalBiome);
                String[][] colorGetters = {
                        {"grass_color", "grassColorOverride"},
                        {"foliage_color", "foliageColorOverride"},
                        {"dry_foliage_color", "dryFoliageColorOverride"},
                        {"water_color", "waterColor"}
                };
                for (String[] color : colorGetters) {
                    Object element = jsonObject.getMethod("get", String.class).invoke(effectsJson, color[0]);
                    if (element == null) continue;
                    String text = (String) jsonElement.getMethod("getAsString").invoke(element);
                    int expected = text.startsWith("#") ? 0xff000000 | (int) Long.parseLong(text.substring(1), 16)
                            : Integer.parseInt(text);
                    Object actual = effects.getClass().getMethod(color[1]).invoke(effects);
                    if (actual instanceof java.util.Optional<?> optional) actual = optional.orElseThrow();
                    if (!Integer.valueOf(expected).equals(actual))
                        throw new AssertionError("Decoded color mismatch: " + file + " / " + color[0]
                                + " expected=" + Integer.toHexString(expected)
                                + " actual=" + Integer.toHexString(((Number) actual).intValue()));
                }
                decoded++;
                // In-memory-only additions verify planned attributes even before finish script runs.
                Object root = asObject.invoke(json);
                Object map = getObject.invoke(root, "attributes");
                if (map == null) {
                    map = jsonObject.getConstructor().newInstance();
                    jsonObject.getMethod("add", String.class, jsonElement).invoke(root, "attributes", map);
                }
                addColor.invoke(map, "minecraft:visual/fog_color", "#b7c8d9");
                addColor.invoke(map, "minecraft:visual/sky_color", "#8caadd");
                addColor.invoke(map, "minecraft:visual/cloud_color", "#ddffffff");
                Object biome = getOrThrow.invoke(parse.invoke(codec, ops, json));
                Object nativeAttributes = attributes.invoke(biome);
                String[] names = {"FOG_COLOR", "SKY_COLOR", "CLOUD_COLOR"};
                int[] expectedColors = {0xffb7c8d9, 0xff8caadd, 0xddffffff};
                for (int i = 0; i < names.length; i++) {
                    Object attribute = attributesType.getField(names[i]).get(null);
                    if (!(boolean) contains.invoke(nativeAttributes, attribute))
                        throw new AssertionError("Attribute silently dropped: " + names[i]);
                    Object color = nativeAttributes.getClass().getMethod("applyModifier",
                            attribute.getClass(), Object.class).invoke(nativeAttributes, attribute, 0);
                    if (!Integer.valueOf(expectedColors[i]).equals(color))
                        throw new AssertionError("Decoded atmosphere color mismatch: " + names[i]);
                }
                withAtmosphere++;
            } catch (InvocationTargetException error) {
                throw new IllegalStateException("Native biome decoding failed: " + file, error.getCause());
            }
        }
        System.out.println("PASS Biome.DIRECT_CODEC + RegistryOps: " + decoded
                + " files; " + withAtmosphere + " additional in-memory fog/sky/cloud variants");
        Class.forName("net.minecraft.server.Bootstrap").getMethod("shutdownStdout").invoke(null);
    }
}
