package dev.ceseasons.platform.v26_3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Verifies the shared 26.1.2/26.2/26.3 wire contract against the running server.
 * Uses an isolated synthetic registry, never a live world or registry mutation.
 * The historical package name does not select a protocol version.
 */
final class BiomeWireFormatProbe {
    private BiomeWireFormatProbe() {}

    static void verify() throws ReflectiveOperationException {
        Class<?> idMap = Class.forName("net.minecraft.core.IdMap");
        Class<?> mapperType = Class.forName("net.minecraft.core.IdMapper");
        Class<?> strategyType = Class.forName("net.minecraft.world.level.chunk.Strategy");
        Class<?> containerType = Class.forName("net.minecraft.world.level.chunk.PalettedContainer");
        Class<?> bufferType = Class.forName("net.minecraft.network.FriendlyByteBuf");
        Constructor<?> bufferConstructor = bufferType.getConstructor(ByteBuf.class);
        Constructor<?> containerConstructor = containerType.getConstructor(Object.class, strategyType);
        Method add = mapperType.getMethod("add", Object.class);
        Method set = containerType.getMethod("set", int.class, int.class, int.class, Object.class);
        Method get = containerType.getMethod("get", int.class, int.class, int.class);
        Method write = containerType.getMethod("write", bufferType);
        Method read = containerType.getMethod("read", bufferType);
        Method createStrategy = strategyType.getMethod("createForBiomes", idMap);

        // Exercise registry-dependent direct widths as well as every local width.
        for (int registrySize : new int[]{64, 871, 1025}) {
            Object registry = mapperType.getConstructor().newInstance();
            Object[] values = new Object[registrySize];
            for (int i = 0; i < values.length; i++) {
                values[i] = new Object();
                add.invoke(registry, values[i]);
            }
            Object strategy = createStrategy.invoke(null, registry);
            for (int distinct : new int[]{1, 2, 4, 8, 16}) {
                Object original = containerConstructor.newInstance(values[0], strategy);
                for (int cell = 0; cell < 64; cell++)
                    set.invoke(original, cell & 3, cell >> 4, (cell >> 2) & 3, values[cell % distinct]);
                ByteBuf encoded = Unpooled.buffer();
                byte[] source;
                try {
                    write.invoke(original, bufferConstructor.newInstance(encoded));
                    source = new byte[encoded.readableBytes()];
                    encoded.readBytes(source);
                } finally { encoded.release(); }
                int expectedBits = distinct == 1 ? 0 : distinct <= 8
                        ? Integer.numberOfTrailingZeros(distinct)
                        : 32 - Integer.numberOfLeadingZeros(registrySize - 1);
                if (source.length == 0 || (source[0] & 255) != expectedBits)
                    throw new IllegalStateException("Unsupported native biome palette width");
                byte[] snapshot = source.clone();
                if (BiomePayloadCodec.remap(source, 1, registrySize, id -> id) != source)
                    throw new IllegalStateException("Biome identity remap changed native payload");
                byte[] remapped = BiomePayloadCodec.remap(source, 1, registrySize,
                        id -> registrySize - 1 - id);
                if (!Arrays.equals(source, snapshot))
                    throw new IllegalStateException("Biome remap mutated shared native payload");
                Object decoded = containerConstructor.newInstance(values[0], strategy);
                ByteBuf input = Unpooled.wrappedBuffer(remapped);
                try {
                    read.invoke(decoded, bufferConstructor.newInstance(input));
                    if (input.isReadable())
                        throw new IllegalStateException("Native biome decoder left trailing bytes");
                    for (int cell = 0; cell < 64; cell++) {
                        Object actual = get.invoke(decoded, cell & 3, cell >> 4, (cell >> 2) & 3);
                        if (actual != values[registrySize - 1 - cell % distinct])
                            throw new IllegalStateException("Native biome codec round-trip mismatch");
                    }
                } finally { input.release(); }
            }
        }
    }
}
