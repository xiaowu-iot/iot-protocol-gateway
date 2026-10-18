package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BcdUtilsTest {

    @Test
    void phoneNumber() {
        ByteBuf buf = Unpooled.buffer();
        BcdUtils.writeBcd(buf, "13800138000", 6);
        assertEquals("013800138000", HexUtils.toHex(buf));
        assertEquals("013800138000", BcdUtils.readBcd(buf, 6));
    }

    @Test
    void phoneNumber2019() {
        // 2019 版手机号是 10 字节 BCD
        ByteBuf buf = Unpooled.buffer();
        BcdUtils.writeBcd(buf, "13800138000", 10);
        assertEquals("00000000013800138000", HexUtils.toHex(buf));
    }

    @Test
    void paddingF() {
        ByteBuf buf = Unpooled.wrappedBuffer(HexUtils.fromHex("1380013800FF"));
        assertEquals("1380013800FF", BcdUtils.readBcd(buf, 6));
    }

    @Test
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> BcdUtils.writeBcd(Unpooled.buffer(), "1234567", 3));
        assertThrows(IllegalArgumentException.class, () -> BcdUtils.writeBcd(Unpooled.buffer(), "12a4", 2));
    }

    @Test
    void time() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 20, 8, 30, 5);
        ByteBuf buf = Unpooled.buffer();
        BcdUtils.writeBcdTime(buf, time);
        assertEquals("261020083005", HexUtils.toHex(buf));
        assertEquals(time, BcdUtils.readBcdTime(buf));
    }

    @Test
    void zeroTime() {
        ByteBuf buf = Unpooled.buffer();
        BcdUtils.writeBcdTime(buf, null);
        assertEquals("000000000000", HexUtils.toHex(buf));
        assertNull(BcdUtils.readBcdTime(buf));
    }

    @Test
    void invalidTime() {
        ByteBuf buf = Unpooled.wrappedBuffer(HexUtils.fromHex("261320083005"));
        assertThrows(IllegalArgumentException.class, () -> BcdUtils.readBcdTime(buf));
    }
}
