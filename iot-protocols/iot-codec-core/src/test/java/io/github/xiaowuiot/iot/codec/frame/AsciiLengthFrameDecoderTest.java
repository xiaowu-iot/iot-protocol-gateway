package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.readAscii;
import static io.github.xiaowuiot.iot.codec.frame.FrameTestSupport.writeAscii;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 HJ212 的报文格式测试：## + 4 位长度 + 数据段 + 4 位 CRC + \r\n
 */
class AsciiLengthFrameDecoderTest {

    static final String DATA = "QN=20160801085857223;ST=32;CN=1062;PW=100000;"
            + "MN=010000A8900016F000169DC0;Flag=5;CP=&&RtdInterval=30&&";

    static final String FRAME = hj212(DATA);
    private static final String FRAME2 = hj212("ST=32;CN=2011;CP=&&a01001-Rtd=25.6&&");

    private final List<DiscardReason> discards = new ArrayList<>();
    private EmbeddedChannel ch;

    static String hj212(String data) {
        int crc = Checksums.crc16Hj212(data.getBytes(StandardCharsets.US_ASCII));
        return String.format("##%04d%s%04X\r\n", data.length(), data, crc);
    }

    @BeforeEach
    void setUp() {
        ch = new EmbeddedChannel(new AsciiLengthFrameDecoder("##", 4, 4, "\r\n", 1024) {
            @Override
            protected void onDiscard(ChannelHandlerContext ctx, ByteBuf discarded, DiscardReason reason) {
                discards.add(reason);
            }
        });
    }

    @Test
    void specExample() {
        // HJ212-2017 标准中的示例报文
        assertEquals("##0101" + DATA + "1C80\r\n", FRAME);
    }

    @Test
    void singleFrame() {
        writeAscii(ch, FRAME);
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
    }

    @Test
    void stickyFrames() {
        writeAscii(ch, FRAME + FRAME2);
        assertEquals(Arrays.asList(FRAME, FRAME2), readAscii(ch));
    }

    @Test
    void halfFrame() {
        // 在长度字段中间拆开
        writeAscii(ch, FRAME.substring(0, 4));
        assertTrue(readAscii(ch).isEmpty());
        writeAscii(ch, FRAME.substring(4, 50));
        assertTrue(readAscii(ch).isEmpty());
        writeAscii(ch, FRAME.substring(50));
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
    }

    @Test
    void prefixSplitAcrossReads() {
        writeAscii(ch, "#");
        writeAscii(ch, FRAME.substring(1));
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
        assertTrue(discards.isEmpty());
    }

    @Test
    void garbageBeforeFrame() {
        writeAscii(ch, "\r\nxx" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
        assertEquals(Collections.singletonList(DiscardReason.GARBAGE), discards);
    }

    @Test
    void badLength() {
        writeAscii(ch, "##01X1" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
        assertEquals(Arrays.asList(DiscardReason.BAD_LENGTH, DiscardReason.GARBAGE), discards);
    }

    @Test
    void badTrailer() {
        // 声明长度 5，按长度算出来的帧尾是 zz 而不是 \r\n，说明这一帧不可信，跳过后找到下一帧
        writeAscii(ch, "##0005ABCDE1234zz" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
        assertEquals(Arrays.asList(DiscardReason.BAD_TRAILER, DiscardReason.GARBAGE), discards);
    }

    @Test
    void tooLong() {
        writeAscii(ch, "##9999" + FRAME);
        assertEquals(Collections.singletonList(FRAME), readAscii(ch));
        assertEquals(Arrays.asList(DiscardReason.TOO_LONG, DiscardReason.GARBAGE), discards);
    }
}
