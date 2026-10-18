package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexUtilsTest {

    @Test
    void roundTrip() {
        byte[] bytes = {0x7E, 0x02, 0x00, (byte) 0xFF, 0x7D};
        assertEquals("7E0200FF7D", HexUtils.toHex(bytes));
        assertArrayEquals(bytes, HexUtils.fromHex("7E0200FF7D"));
    }

    @Test
    void ignoreWhitespaceAndCase() {
        assertArrayEquals(new byte[]{0x7E, (byte) 0xAB, 0x01},
                HexUtils.fromHex("7e ab\n 01"));
    }

    @Test
    void byteBufNotConsumed() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02, 0x03});
        buf.readByte();
        assertEquals("0203", HexUtils.toHex(buf));
        assertEquals(1, buf.readerIndex());
        buf.release();
    }

    @Test
    void rejectIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("7E0"));
        assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("7G"));
    }
}
