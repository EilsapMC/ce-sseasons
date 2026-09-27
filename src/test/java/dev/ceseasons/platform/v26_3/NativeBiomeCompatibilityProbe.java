package dev.ceseasons.platform.v26_3;

/**
 * Explicit integration probe: run with one real server jar and its libraries.
 * Not a mock-based unit test: a missing NMS class is an error, never a skip.
 */
public final class NativeBiomeCompatibilityProbe {
    public static void main(String[] args) throws ReflectiveOperationException {
        BiomeWireFormatProbe.verify();
        System.out.println("PASS native biome round-trips (15 palettes / 960 cells)");
        Class<?> biome = Class.forName("net.minecraft.world.level.biome.Biome", false,
                NativeBiomeCompatibilityProbe.class.getClassLoader());
        if (biome.getMethod("getBaseTemperature").getReturnType() != float.class
                || biome.getMethod("hasPrecipitation").getReturnType() != boolean.class)
            throw new AssertionError("Unexpected climate biome accessors");
        ClassLoader loader = NativeBiomeCompatibilityProbe.class.getClassLoader();
        Class<?> pos = Class.forName("net.minecraft.world.level.ChunkPos", false, loader);
        Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket", false, loader);
        Class<?> chunk = Class.forName(packet.getName() + "$ChunkBiomeData", false, loader);
        packet.getConstructor(java.util.List.class);
        chunk.getConstructor(pos, byte[].class);
        if (packet.getMethod("chunkBiomeData").getReturnType() != java.util.List.class
                || chunk.getMethod("pos").getReturnType() != pos
                || chunk.getMethod("buffer").getReturnType() != byte[].class)
            throw new AssertionError("Unexpected biome packet record");
        Class<?> spawn = Class.forName("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo", false, loader);
        for (String name : new String[]{"ClientboundLoginPacket", "ClientboundRespawnPacket"})
            if (Class.forName("net.minecraft.network.protocol.game." + name, false, loader)
                    .getMethod("commonPlayerSpawnInfo").getReturnType() != spawn)
                throw new AssertionError("Unexpected dimension barrier record");
        Class.forName("net.minecraft.network.PacketEncoder", false, loader);
        Class.forName("net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask", false, loader);
        Class.forName("net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket", false, loader);
        if (args.length > 0 && args[0].equals("folia")) {
            Class<?> level = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> region = level.getMethod("getCurrentWorldData").getReturnType();
            if (region.getField("captureTreeGeneration").getType() != boolean.class)
                throw new AssertionError("Unexpected Folia bone meal capture flag");
            Class.forName("org.bukkit.craftbukkit.block.CraftBiome", false, loader).getMethod("getHandle");
        }
        Class<?> key = Class.forName("net.minecraft.resources.ResourceKey");
        if (spawn.getMethod("dimension").getReturnType() != key)
            throw new AssertionError("Unexpected dimension resource key");
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");
        if (key.getMethod("identifier").getReturnType() != identifier)
            throw new AssertionError("Unexpected dimension identifier accessor");
        Class<?> registry = Class.forName("net.minecraft.core.Registry");
        registry.getMethod("getValue", identifier);
        registry.getMethod("getKey", Object.class);
        registry.getMethod("getId", Object.class);
        Class.forName("net.minecraft.core.RegistryAccess").getMethod("lookupOrThrow", key);
        Class.forName("net.minecraft.world.level.BlockAndLightGetter").getMethod("canSeeSky",
                Class.forName("net.minecraft.core.BlockPos"));
        System.out.println("PASS packet, dimension, registry and climate contracts"
                + (args.length > 0 && args[0].equals("folia") ? "; Folia capture and CraftBiome bridges" : ""));
    }
}
