package io.github.xiaowuiot.iot.hj212.codec;

import io.github.xiaowuiot.iot.codec.frame.AsciiLengthFrameDecoder;
import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.hj212.Hj212Format;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 212 的 Netty 编解码器。
 * <p>
 * 和 808 对比一下就能看出"多协议"的规律：切帧用 codec-core 的通用帧解码器（换个参数），
 * 协议相关的只有"一帧 → 对象"这一步。
 */
public final class Hj212Codecs {

    private static final Logger log = LoggerFactory.getLogger(Hj212Codecs.class);

    /**
     * 长度字段只有 4 位十进制，数据段最多 9999 字节
     */
    public static final int MAX_DATA_LENGTH = 9999;

    /**
     * ## + 4 位长度
     */
    private static final int HEADER_LENGTH = 6;

    /**
     * 4 位 CRC + \r\n
     */
    private static final int TRAILER_LENGTH = 6;

    private static final Encoder ENCODER = new Encoder();

    private Hj212Codecs() {
    }

    public static void addTo(ChannelPipeline pipeline) {
        pipeline.addLast("hj212FrameDecoder", new AsciiLengthFrameDecoder("##", 4, 4, "\r\n", MAX_DATA_LENGTH))
                .addLast("hj212Decoder", new Decoder())
                .addLast("hj212Encoder", ENCODER);
    }

    /**
     * 一帧 → {@link Hj212Packet}：校验 CRC、解析数据段。
     */
    @ChannelHandler.Sharable
    public static class Decoder extends MessageToMessageDecoder<ByteBuf> {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
            int start = frame.readerIndex();
            int dataLength = frame.readableBytes() - HEADER_LENGTH - TRAILER_LENGTH;
            int expected;
            try {
                expected = Integer.parseInt(frame.toString(start + HEADER_LENGTH + dataLength, 4, StandardCharsets.US_ASCII), 16);
            } catch (NumberFormatException e) {
                drop(ctx, frame, "CRC 不是十六进制");
                return;
            }
            int actual = Checksums.crc16Hj212(frame, start + HEADER_LENGTH, dataLength);
            if (expected != actual) {
                drop(ctx, frame, String.format("CRC 校验失败，报文中是 %04X，计算结果是 %04X", expected, actual));
                return;
            }
            String data = frame.toString(start + HEADER_LENGTH, dataLength, StandardCharsets.US_ASCII);
            try {
                out.add(Hj212Format.parse(data));
            } catch (IllegalArgumentException e) {
                drop(ctx, frame, e.getMessage());
            }
        }

        private static void drop(ChannelHandlerContext ctx, ByteBuf frame, String reason) {
            CodecStats.increment("hj212.drop");
            // 212 是文本协议，日志里直接打印文本比十六进制好读
            String text = frame.toString(StandardCharsets.US_ASCII).trim();
            log.warn("{} 丢弃一帧：{}，内容：{}", ctx.channel(), reason,
                    text.length() > 512 ? text.substring(0, 512) + "..." : text);
            if (log.isDebugEnabled()) {
                log.debug("十六进制：{}", HexUtils.toHex(frame));
            }
        }
    }

    @ChannelHandler.Sharable
    public static class Encoder extends MessageToByteEncoder<Hj212Packet> {

        @Override
        protected void encode(ChannelHandlerContext ctx, Hj212Packet msg, ByteBuf out) {
            out.writeCharSequence(Hj212Format.frame(Hj212Format.format(msg)), StandardCharsets.US_ASCII);
        }
    }
}
