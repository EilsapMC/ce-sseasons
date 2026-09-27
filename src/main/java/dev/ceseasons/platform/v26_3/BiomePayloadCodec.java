package dev.ceseasons.platform.v26_3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.function.IntUnaryOperator;

/**
 * 26.3 biome PalettedContainer wire format: 64 entries, 0/single, 1..3/local,
 * otherwise registry-width direct; fixed-size padded long data (NO long-array length).
 * All output is per invocation. No original byte array is ever modified.
 */
public final class BiomePayloadCodec {
    private BiomePayloadCodec() {}

    public static byte[] remap(byte[] source, int sections, int registrySize, IntUnaryOperator mapping) {
        if (source.length > 2_097_152 || sections <= 0 || sections > 1024 || registrySize <= 0)
            throw new IllegalArgumentException("Invalid 26.3 biome payload bounds");
        ByteBuf input = Unpooled.wrappedBuffer(source);
        ByteBuf output = Unpooled.buffer(source.length);
        try {
            boolean changed = false;
            int directBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(registrySize - 1));
            for (int section = 0; section < sections; section++) {
                int bits = input.readUnsignedByte();
                if (bits == 0) {
                    int before = readVarInt(input);
                    int after = map(before, registrySize, mapping);
                    changed |= before != after;
                    output.writeByte(0);
                    writeVarInt(output, after);
                } else if (bits <= 3) {
                    int size = readVarInt(input);
                    if (size < 1 || size > (1 << bits)) throw new IllegalArgumentException("Invalid local biome palette size");
                    output.writeByte(bits);
                    writeVarInt(output, size);
                    for (int i = 0; i < size; i++) {
                        int before = readVarInt(input);
                        int after = map(before, registrySize, mapping);
                        changed |= before != after;
                        writeVarInt(output, after);
                    }
                    int perLong = 64 / bits;
                    int longs = (64 + perLong - 1) / perLong;
                    long mask = (1L << bits) - 1;
                    for (int i = 0; i < longs; i++) {
                        long packed = input.readLong();
                        for (int cell = 0; cell < perLong && i * perLong + cell < 64; cell++)
                            if (((packed >>> (cell * bits)) & mask) >= size)
                                throw new IllegalArgumentException("Biome palette index out of bounds");
                        output.writeLong(packed);
                    }
                } else {
                    if (bits != directBits || bits > 31)
                        throw new IllegalArgumentException("Direct biome width differs from server registry: " + bits + "/" + directBits);
                    output.writeByte(bits);
                    int perLong = 64 / bits;
                    int longs = (64 + perLong - 1) / perLong;
                    long mask = (1L << bits) - 1;
                    for (int i = 0; i < longs; i++) {
                        long packed = input.readLong();
                        long rewritten = packed; // preserve padding bits
                        for (int cell = 0; cell < perLong && i * perLong + cell < 64; cell++) {
                            int shift = cell * bits;
                            int before = (int) ((packed >>> shift) & mask);
                            int after = map(before, registrySize, mapping);
                            changed |= before != after;
                            rewritten = (rewritten & ~(mask << shift)) | ((long) after << shift);
                        }
                        output.writeLong(rewritten);
                    }
                }
            }
            if (input.isReadable()) throw new IllegalArgumentException("Biome payload trailing bytes; stale section context");
            if (!changed) return source;
            byte[] result = new byte[output.readableBytes()];
            output.readBytes(result);
            return result;
        } finally { input.release(); output.release(); }
    }

    private static int map(int before, int registrySize, IntUnaryOperator mapping) {
        if (before < 0 || before >= registrySize) throw new IllegalArgumentException("Biome ID outside server registry");
        int result = mapping.applyAsInt(before);
        if (result < 0 || result >= registrySize) throw new IllegalArgumentException("Mapped biome ID outside server registry");
        return result;
    }

    private static int readVarInt(ByteBuf buffer) {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int part = buffer.readUnsignedByte();
            if (i == 4 && (part & 0xf0) != 0) throw new IllegalArgumentException("Biome VarInt overflow");
            result |= (part & 127) << (7 * i);
            if ((part & 128) == 0) return result;
        }
        throw new IllegalArgumentException("Biome VarInt too long");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & ~127) != 0) { buffer.writeByte((value & 127) | 128); value >>>= 7; }
        buffer.writeByte(value);
    }
}
