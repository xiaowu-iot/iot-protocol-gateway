package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteBufUtilsTest {

    @Test
    void indexOf() {
        ByteBuf buf = Unpooled.wrappedBuffer(HexUtils.fromHex("00 30 31 63 64 01"));
        byte[] header = HexUtils.fromHex("30316364");
        assertEquals(1, ByteBufUtils.indexOf(buf, header, 0, buf.writerIndex()));
        assertEquals(-1, ByteBufUtils.indexOf(buf, header, 2, buf.writerIndex()));
        // 只剩半个帧头时找不到
        assertEquals(-1, ByteBufUtils.indexOf(buf, header, 0, 4));
    }

    @Test
    void matches() {
        ByteBuf buf = Unpooled.wrappedBuffer(HexUtils.fromHex("0D0A"));
        assertTrue(ByteBufUtils.matches(buf, 0, new byte[]{0x0D, 0x0A}));
        assertFalse(ByteBufUtils.matches(buf, 1, new byte[]{0x0A, 0x00}));
    }

    @Test
    void fixedLengthGbkString() {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeString(buf, "粤B12345", 12, ByteBufUtils.GBK);
        assertEquals(12, buf.readableBytes());
        assertEquals("D4C1423132333435" + "00000000", HexUtils.toHex(buf));
        assertEquals("粤B12345", ByteBufUtils.readString(buf, 12, ByteBufUtils.GBK));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void stringTooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> ByteBufUtils.writeString(Unpooled.buffer(), "粤B12345", 7, ByteBufUtils.GBK));
    }

    @Test
    void byteLengthString() {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeByteLengthString(buf, "AUTH-1234", ByteBufUtils.GBK);
        assertEquals(9, buf.getUnsignedByte(0));
        assertEquals("AUTH-1234", ByteBufUtils.readByteLengthString(buf, ByteBufUtils.GBK));
    }
}
