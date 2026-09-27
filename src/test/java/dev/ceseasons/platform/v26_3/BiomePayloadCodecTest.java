package dev.ceseasons.platform.v26_3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BiomePayloadCodecTest {
    private static byte[] bytes(ByteBuf buffer) {
        try {
            byte[] result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally { buffer.release(); }
    }

    @Test void singletonExpandsVarIntWithoutMutatingSharedInput() {
        byte[] source = {0, 7};
        byte[] result = BiomePayloadCodec.remap(source, 1, 1000, id -> id == 7 ? 804 : id);
        assertArrayEquals(new byte[]{0, (byte) 0xa4, 6}, result);
        assertArrayEquals(new byte[]{0, 7}, source);
        assertSame(source, BiomePayloadCodec.remap(source, 1, 1000, id -> id));
    }

    @Test void localPalettePreservesPackedIndicesAndNoArrayLengthPrefix() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(1).writeByte(2).writeByte(1).writeByte(7);
        long bits = 0x5555555555555555L;
        payload.writeLong(bits);
        byte[] source = bytes(payload);
        byte[] result = BiomePayloadCodec.remap(source, 1, 1000, id -> id == 7 ? 804 : id);
        assertEquals(13, result.length);
        ByteBuf read = Unpooled.wrappedBuffer(result);
        try {
            assertEquals(1, read.readUnsignedByte());
            assertEquals(2, read.readUnsignedByte());
            assertEquals(1, read.readUnsignedByte());
            assertEquals(0xa4, read.readUnsignedByte());
            assertEquals(6, read.readUnsignedByte());
            assertEquals(bits, read.readLong());
            assertFalse(read.isReadable());
        } finally { read.release(); }
        assertArrayEquals(new byte[]{1, 2, 1, 7}, Arrays.copyOf(source, 4));
    }

    @Test void directPaletteUsesPaddedLongsAndRegistryWidth() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(10); // ceilLog2(871)
        int perLong = 6;
        for (int i = 0; i < 11; i++) {
            long packed = 0;
            for (int j = 0; j < perLong && i * perLong + j < 64; j++) packed |= 7L << (j * 10);
            payload.writeLong(packed);
        }
        byte[] source = bytes(payload);
        byte[] result = BiomePayloadCodec.remap(source, 1, 871, id -> id == 7 ? 870 : id);
        assertEquals(89, result.length);
        ByteBuf read = Unpooled.wrappedBuffer(result);
        try {
            assertEquals(10, read.readUnsignedByte());
            for (int i = 0; i < 11; i++) {
                long packed = read.readLong();
                for (int j = 0; j < perLong && i * perLong + j < 64; j++)
                    assertEquals(870, (packed >>> (j * 10)) & 1023);
            }
        } finally { read.release(); }
        assertSame(result, BiomePayloadCodec.remap(result, 1, 871, id -> id == 7 ? 870 : id));
    }

    @Test void multipleSectionsAndUnknownRegisteredBiomesPassThrough() {
        byte[] source = {0, 7, 0, 50};
        byte[] result = BiomePayloadCodec.remap(source, 2, 871, id -> id == 7 ? 100 : id);
        assertArrayEquals(new byte[]{0, 100, 0, 50}, result);
        assertArrayEquals(new byte[]{0, 7, 0, 50}, source);
    }

    @Test void rejectsWrongWidthTrailingAndTruncatedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> BiomePayloadCodec.remap(new byte[]{9}, 1, 871, id -> id));
        assertThrows(IllegalArgumentException.class, () -> BiomePayloadCodec.remap(new byte[]{0, 7, 0}, 1, 871, id -> id));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePayloadCodec.remap(new byte[]{0}, 1, 871, id -> id));
        assertThrows(IllegalArgumentException.class, () -> BiomePayloadCodec.remap(new byte[]{1, 3}, 1, 871, id -> id));
        assertThrows(IllegalArgumentException.class, () -> BiomePayloadCodec.remap(new byte[]{0, 7}, 1, 871, id -> 871));
    }
}
