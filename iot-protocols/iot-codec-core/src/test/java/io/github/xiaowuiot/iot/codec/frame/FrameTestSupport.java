package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.ByteEscaper;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧解码器测试的公共方法。用 EmbeddedChannel 模拟数据到达，不需要真的建立 TCP 连接。
 */
final class FrameTestSupport {

    /**
     * 808 的转义规则：0x7D → 7D 01，0x7E → 7D 02
     */
    static final ByteEscaper JT808_ESCAPER = new ByteEscaper((byte) 0x7D,
            new byte[]{0x7D, 0x7E}, new byte[]{0x01, 0x02});

    private FrameTestSupport() {
    }

    static void writeHex(EmbeddedChannel ch, String hex) {
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(hex)));
    }

    static void writeAscii(EmbeddedChannel ch, String text) {
        ch.writeInbound(Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
    }

    /**
     * 取出所有已解码的帧，转成十六进制，并释放 ByteBuf。
     */
    static List<String> readHex(EmbeddedChannel ch) {
        List<String> frames = new ArrayList<>();
        ByteBuf buf;
        while ((buf = ch.readInbound()) != null) {
            frames.add(HexUtils.toHex(buf));
            buf.release();
        }
        return frames;
    }

    static List<String> readAscii(EmbeddedChannel ch) {
        List<String> frames = new ArrayList<>();
        ByteBuf buf;
        while ((buf = ch.readInbound()) != null) {
            frames.add(buf.toString(StandardCharsets.US_ASCII));
            buf.release();
        }
        return frames;
    }
}
