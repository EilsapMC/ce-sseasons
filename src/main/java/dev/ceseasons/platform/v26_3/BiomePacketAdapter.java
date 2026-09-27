package dev.ceseasons.platform.v26_3;

import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Exact 26.3 reflection boundary; no compile dependency on NMS or mutation of shared packets. */
public final class BiomePacketAdapter {
    private final Class<?> packetType;
    private final Class<?> encoderType;
    private final Class<?> loginType;
    private final Class<?> respawnType;
    private final Class<?> configurationType;
    private final Class<?> outboundConfigurationTask;
    private final Constructor<?> packetConstructor;
    private final Constructor<?> chunkConstructor;
    private final Method chunks;
    private final Method position;
    private final Method buffer;
    private final Method loginSpawn;
    private final Method respawnSpawn;
    private final Method spawnDimension;
    private final Method keyIdentifier;

    public BiomePacketAdapter() throws ReflectiveOperationException {
        packetType = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket");
        Class<?> chunkType = Class.forName(packetType.getName() + "$ChunkBiomeData");
        Class<?> posType = Class.forName("net.minecraft.world.level.ChunkPos");
        encoderType = Class.forName("net.minecraft.network.PacketEncoder");
        loginType = Class.forName("net.minecraft.network.protocol.game.ClientboundLoginPacket");
        respawnType = Class.forName("net.minecraft.network.protocol.game.ClientboundRespawnPacket");
        configurationType = Class.forName("net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket");
        outboundConfigurationTask = Class.forName("net.minecraft.network.UnconfiguredPipelineHandler$OutboundConfigurationTask");
        packetConstructor = packetType.getConstructor(List.class);
        chunkConstructor = chunkType.getConstructor(posType, byte[].class);
        chunks = packetType.getMethod("chunkBiomeData");
        position = chunkType.getMethod("pos");
        buffer = chunkType.getMethod("buffer");
        loginSpawn = loginType.getMethod("commonPlayerSpawnInfo");
        respawnSpawn = respawnType.getMethod("commonPlayerSpawnInfo");
        Class<?> spawnType = Class.forName("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo");
        spawnDimension = spawnType.getMethod("dimension");
        keyIdentifier = Class.forName("net.minecraft.resources.ResourceKey").getMethod("identifier");
        if (chunks.getReturnType() != List.class || position.getReturnType() != posType
                || buffer.getReturnType() != byte[].class) {
            throw new NoSuchMethodException("Unexpected 26.3 biome packet record signatures");
        }
    }

    public boolean isEncoder(Object handler) { return encoderType.isInstance(handler); }
    public boolean isOutboundConfigurationTask(Object message) { return outboundConfigurationTask.isInstance(message); }
    public boolean isBiomePacket(Object packet) { return packetType.isInstance(packet); }
    public boolean isDimensionBarrier(Object packet) {
        return loginType.isInstance(packet) || respawnType.isInstance(packet) || configurationType.isInstance(packet);
    }
    public boolean isConfigurationBarrier(Object packet) { return configurationType.isInstance(packet); }

    public String dimension(Object packet) throws ReflectiveOperationException {
        Method accessor = loginType.isInstance(packet) ? loginSpawn : respawnSpawn;
        return keyIdentifier.invoke(spawnDimension.invoke(accessor.invoke(packet))).toString();
    }

    public Object copyAndRemap(Object packet, BukkitServerPlayer player, int sections,
                               int registrySize, IntUnaryOperator remapper) throws ReflectiveOperationException {
        if (player.clientBiomeList().size() != registrySize) {
            throw new IllegalStateException("CE client/server biome idList size mismatch; client-only registry append is unsupported");
        }
        List<?> source = (List<?>) chunks.invoke(packet);
        if (source.size() > 4096 || sections <= 0 || sections > 1024) {
            throw new IllegalArgumentException("Invalid 26.3 biome packet dimensions");
        }
        List<Object> replacement = new ArrayList<>(source.size());
        boolean anyChanged = false;
        for (Object chunk : source) {
            byte[] original = (byte[]) buffer.invoke(chunk);
            byte[] changed = BiomePayloadCodec.remap(original, sections, registrySize, remapper);
            if (changed != original) anyChanged = true;
            replacement.add(changed == original ? chunk : chunkConstructor.newInstance(position.invoke(chunk), changed));
        }
        return anyChanged ? packetConstructor.newInstance(List.copyOf(replacement)) : packet;
    }
}
