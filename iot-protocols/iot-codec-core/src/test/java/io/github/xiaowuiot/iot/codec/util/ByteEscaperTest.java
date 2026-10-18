package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteEscaperTest {

    private final ByteEscaper escaper = new ByteEscaper((byte) 0x7D,
            new byte[]{0x7D, 0x7E}, new byte[]{0x01, 0x02});

    @Test
    void escapeAndUnescape() {
        ByteBuf escaped = Unpooled.buffer();
        escaper.escape(Unpooled.wrappedBuffer(HexUtils.fromHex("30 7E 08 7D 55")), escaped);
        assertEquals("307D02087D0155", HexUtils.toHex(escaped));

        ByteBuf raw = Unpooled.buffer();
        assertTrue(escaper.unescape(escaped, 0, escaped.readableBytes(), raw));
        assertEquals("307E087D55", HexUtils.toHex(raw));
    }

    @Test
    void escapeAtEnd() {
        ByteBuf src = Unpooled.wrappedBuffer(HexUtils.fromHex("30 7D"));
        assertFalse(escaper.unescape(src, 0, 2, Unpooled.buffer()));
    }

    @Test
    void invalidCode() {
        ByteBuf src = Unpooled.wrappedBuffer(HexUtils.fromHex("7D 03"));
        assertFalse(escaper.unescape(src, 0, 2, Unpooled.buffer()));
    }

    @Test
    void escapeCharMustEscapeItself() {
        assertThrows(IllegalArgumentException.class,
                () -> new ByteEscaper((byte) 0x7D, new byte[]{0x7E}, new byte[]{0x02}));
    }
}
