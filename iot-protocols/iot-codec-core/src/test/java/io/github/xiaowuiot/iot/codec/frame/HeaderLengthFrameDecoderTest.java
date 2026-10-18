package io.github.xiaowuiot.iot.codec.frame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.readHex;
import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试用的帧格式：AA55 | 命令(1) | 长度(2) | 数据 | CRC(2)
 */
class HeaderLengthFrameDecoderTest {

    private static final byte[] HEADER = {(byte) 0xAA, 0x55};
    private static final String FRAME = "AA55010002 1122 FFFF".replace(" ", "");
    private static final String FRAME2 = "AA55020001 33 EEEE".replace(" ", "");

    private final List<DiscardReason> discards = new ArrayList<>();
    private EmbeddedChannel ch;

    @BeforeEach
    void setUp() {
        ch = new EmbeddedChannel(new RecordingDecoder(3));
    }

    @Test
    void singleFrame() {
        writeHex(ch, FRAME);
        assertEquals(Collections.singletonList(FRAME), readHex(ch));
    }

    @Test
    void stickyFrames() {
        writeHex(ch, FRAME + FRAME2);
        assertEquals(Arrays.asList(FRAME, FRAME2), readHex(ch));
    }

    @Test
    void headerSplitAcrossReads() {
        // 帧头 AA55 被拆开：第一次只收到 AA，不能当垃圾丢掉
        writeHex(ch, "AA");
        assertTrue(readHex(ch).isEmpty());
        assertTrue(discards.isEmpty());
        writeHex(ch, FRAME.substring(2));
        assertEquals(Collections.singletonList(FRAME), readHex(ch));
    }

    @Test
    void bodySplitAcrossReads() {
        writeHex(ch, FRAME.substring(0, 12));
        assertTrue(readHex(ch).isEmpty());
        writeHex(ch, FRAME.substring(12));
        assertEquals(Collections.singletonList(FRAME), readHex(ch));
    }

    @Test
    void garbageBeforeFrame() {
        writeHex(ch, "00 AA 00" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readHex(ch));
        assertEquals(Collections.singletonList(DiscardReason.GARBAGE), discards);
    }

    @Test
    void resyncAfterBadLength() {
        // 第一个"帧头"后面的长度是 0xFFFF，超过最大帧长，应该跳过它，找到后面真正的帧
        writeHex(ch, "AA5501FFFF" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readHex(ch));
        assertEquals(Arrays.asList(DiscardReason.BAD_LENGTH, DiscardReason.GARBAGE), discards);
    }

    @Test
    void variableLengthFieldOffset() {
        // 模拟 1078 这类协议：命令为 01 时长度在偏移 3，否则前面多了 2 字节，长度在偏移 5
        EmbeddedChannel variable = new EmbeddedChannel(new RecordingDecoder(3) {
            @Override
            protected int lengthFieldOffset(ByteBuf in, int frameStart) {
                if (in.writerIndex() - frameStart < 3) {
                    return -1;
                }
                return in.getByte(frameStart + 2) == 0x01 ? 3 : 5;
            }
        });
        String a = "AA55010001 33 FFFF".replace(" ", "");
        String b = "AA5502EEEE0001 44 FFFF".replace(" ", "");
        writeHex(variable, a + b.substring(0, 4));
        writeHex(variable, b.substring(4));
        assertEquals(Arrays.asList(a, b), readHex(variable));
    }

    @Test
    void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new HeaderLengthFrameDecoder(HEADER, 3, 5, 2, 64));
        assertThrows(IllegalArgumentException.class, () -> new HeaderLengthFrameDecoder(HEADER, 1, 2, 2, 64));
    }

    private class RecordingDecoder extends HeaderLengthFrameDecoder {

        RecordingDecoder(int lengthFieldOffset) {
            super(HEADER, lengthFieldOffset, 2, 2, 64);
        }

        @Override
        protected void onDiscard(ChannelHandlerContext ctx, ByteBuf discarded, DiscardReason reason) {
            discards.add(reason);
        }
    }
}
