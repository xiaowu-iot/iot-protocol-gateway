package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.JT808_ESCAPER;
import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.readHex;
import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscapedDelimiterFrameDecoderTest {

    private final List<DiscardReason> discards = new ArrayList<>();
    private EmbeddedChannel ch;

    @BeforeEach
    void setUp() {
        ch = new EmbeddedChannel(new EscapedDelimiterFrameDecoder((byte) 0x7E, JT808_ESCAPER, 16) {
            @Override
            protected void onDiscard(ChannelHandlerContext ctx, ByteBuf discarded, DiscardReason reason) {
                discards.add(reason);
            }
        });
    }

    @Test
    void singleFrame() {
        writeHex(ch, "7E 0102 7E");
        assertEquals(Collections.singletonList("0102"), readHex(ch));
    }

    @Test
    void stickyFrames() {
        // 粘包：两帧在一次 read 里到达，中间是 7E 7E
        writeHex(ch, "7E 0102 7E 7E 0304 7E");
        assertEquals(Arrays.asList("0102", "0304"), readHex(ch));
        assertTrue(discards.isEmpty());
    }

    @Test
    void halfFrame() {
        // 半包：一帧分两次到达
        writeHex(ch, "7E 01 02");
        assertTrue(readHex(ch).isEmpty());
        writeHex(ch, "03 7E");
        assertEquals(Collections.singletonList("010203"), readHex(ch));
    }

    @Test
    void escapeSplitAcrossReads() {
        // 最容易出 bug 的情况：转义序列 7D 02 被拆到了两次 read 里
        writeHex(ch, "7E 01 7D");
        assertTrue(readHex(ch).isEmpty());
        writeHex(ch, "02 03 7E");
        assertEquals(Collections.singletonList("017E03"), readHex(ch));
    }

    @Test
    void unescape() {
        writeHex(ch, "7E 30 7D02 7D01 31 7E");
        assertEquals(Collections.singletonList("307E7D31"), readHex(ch));
    }

    @Test
    void garbageBeforeFrame() {
        writeHex(ch, "AA BB 7E 01 7E");
        assertEquals(Collections.singletonList("01"), readHex(ch));
        assertEquals(Collections.singletonList(DiscardReason.GARBAGE), discards);
    }

    @Test
    void badFrameDoesNotBlockNextFrame() {
        // 7D 05 是非法转义。坏帧被丢弃后，同一次 read 里的下一帧要立刻解析出来，不能等下次数据
        writeHex(ch, "7E 01 7D05 7E 7E 02 7E");
        assertEquals(Collections.singletonList("02"), readHex(ch));
        assertEquals(Collections.singletonList(DiscardReason.BAD_ESCAPE), discards);
    }

    @Test
    void tooLongWithoutEnd() {
        writeHex(ch, "7E 0102030405060708090A0B0C0D0E0F10");
        assertTrue(readHex(ch).isEmpty());
        assertEquals(Collections.singletonList(DiscardReason.TOO_LONG), discards);

        writeHex(ch, "7E 0A 7E");
        assertEquals(Collections.singletonList("0A"), readHex(ch));
    }

    @Test
    void tooLongWithEnd() {
        writeHex(ch, "7E 0102030405060708090A0B0C0D0E0F10 7E 0A 7E");
        assertEquals(Collections.singletonList("0A"), readHex(ch));
        assertEquals(Collections.singletonList(DiscardReason.TOO_LONG), discards);
    }

    @Test
    void jt808Heartbeat() {
        // 2013 版终端心跳：消息 ID 0x0002，消息体为空，手机号 013800138000，流水号 1，校验码 A9
        EmbeddedChannel ch808 = new EmbeddedChannel(
                new EscapedDelimiterFrameDecoder((byte) 0x7E, JT808_ESCAPER, 1024));
        writeHex(ch808, "7E 0002 0000 013800138000 0001 A9 7E");

        ByteBuf frame = ch808.readInbound();
        assertEquals("000200000138001380000001A9", HexUtils.toHex(frame));
        int bodyLength = frame.readableBytes() - 1;
        assertEquals(frame.getUnsignedByte(bodyLength), Checksums.bcc(frame, 0, bodyLength));
        frame.release();
    }

    @Test
    void jt808Location() {
        // 位置信息汇报 0x0200，消息体 37 字节，校验码 F9
        EmbeddedChannel ch808 = new EmbeddedChannel(
                new EscapedDelimiterFrameDecoder((byte) 0x7E, JT808_ESCAPER, 1024));
        writeHex(ch808, "7E 0200 0025 013800138000 0007"
                + "00000000 00000003 0157FAF8 06CC6289 001E 0258 005A 261020083005"
                + "01 04 00003039 31 01 0C F9 7E");

        ByteBuf frame = ch808.readInbound();
        assertEquals(12 + 37 + 1, frame.readableBytes());
        assertEquals(0x25, frame.getUnsignedShort(2) & 0x03FF);
        assertEquals(0xF9, Checksums.bcc(frame, 0, frame.readableBytes() - 1));
        frame.release();
    }

    @Test
    void jt808HeartbeatWithEscapedFlowId() {
        // 流水号 0x007E 被转义成 7D 02，校验码 D6
        EmbeddedChannel ch808 = new EmbeddedChannel(
                new EscapedDelimiterFrameDecoder((byte) 0x7E, JT808_ESCAPER, 1024));
        writeHex(ch808, "7E 0002 0000 013800138000 007D02 D6 7E");

        ByteBuf frame = ch808.readInbound();
        assertEquals("00020000013800138000007ED6", HexUtils.toHex(frame));
        assertEquals(0x007E, frame.getUnsignedShort(10));
        assertEquals(0xD6, Checksums.bcc(frame, 0, frame.readableBytes() - 1));
        frame.release();
    }

    @Test
    void encoder() {
        EmbeddedChannel out = new EmbeddedChannel(new EscapedDelimiterFrameEncoder((byte) 0x7E, JT808_ESCAPER));
        out.writeOutbound(Unpooled.wrappedBuffer(HexUtils.fromHex("307E7D31")));
        ByteBuf encoded = out.readOutbound();
        assertEquals("7E307D027D01317E", HexUtils.toHex(encoded));
        encoded.release();
    }
}
