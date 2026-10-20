package io.github.xiaowuiot.iot.hj212.codec;

import io.github.xiaowuiot.iot.codec.frame.AsciiLengthFrameDecoder;
import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.hj212.Hj212Crypto;
import io.github.xiaowuiot.iot.hj212.Hj212Format;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
    private static final Decoder DECODER = new Decoder(null);

    private Hj212Codecs() {
    }

    public static void addTo(ChannelPipeline pipeline) {
        addTo(pipeline, null);
    }

    /**
     * @param keys 按 MN 查 2025 版的通信密钥，用来解密加密报文；null 表示不支持加密报文
     */
    public static void addTo(ChannelPipeline pipeline, KeyLookup keys) {
        pipeline.addLast("hj212FrameDecoder", new AsciiLengthFrameDecoder("##", 4, 4, "\r\n", MAX_DATA_LENGTH))
                .addLast("hj212Decoder", keys == null ? DECODER : new Decoder(keys))
                .addLast("hj212Encoder", ENCODER);
    }

    /**
     * 按 MN 查通信密钥（2025 版，16 字节）。可以返回多个：密钥更新的过渡期，设备可能还在用旧密钥。没有返回空列表
     */
    @FunctionalInterface
    public interface KeyLookup {
        KeyLookup NONE = mn -> Collections.emptyList();

        List<byte[]> keys(String mn);
    }

    /**
     * 一帧 → {@link Hj212Packet}：校验 CRC、解析数据段。
     * <p>
     * 2025 版加密传输时 CRC 是按明文算的，报文里也没有"是否加密"的标志：先按收到的字节校验，
     * 对不上再用这台设备的密钥解密 CP 后重新校验，对上了就是加密报文（{@link Hj212Packet#isEncrypted()}）。
     */
    @ChannelHandler.Sharable
    public static class Decoder extends MessageToMessageDecoder<ByteBuf> {

        private final KeyLookup keys;

        public Decoder(KeyLookup keys) {
            this.keys = keys;
        }

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
            byte[] data = ByteBufUtil.getBytes(frame, start + HEADER_LENGTH, dataLength);
            int actual = Checksums.crc16Hj212(data);
            boolean encrypted = false;
            if (expected != actual) {
                data = decrypt(data, expected);
                if (data == null) {
                    drop(ctx, frame, String.format("CRC 校验失败，报文中是 %04X，计算结果是 %04X", expected, actual));
                    return;
                }
                encrypted = true;
            }
            try {
                Hj212Packet p = Hj212Format.parse(new String(data, StandardCharsets.UTF_8));
                out.add(encrypted ? p.markEncrypted() : p);
            } catch (IllegalArgumentException e) {
                drop(ctx, frame, e.getMessage());
            }
        }

        /**
         * 可能是加密报文：从 CP 前面的明文包头取 MN，用这台设备的密钥逐个试，解密后 CRC 对得上才算
         *
         * @return 解密后的数据段，解不开返回 null
         */
        private byte[] decrypt(byte[] data, int expectedCrc) {
            if (keys == null) {
                return null;
            }
            String mn = headerMn(data);
            if (mn == null || mn.isEmpty()) {
                return null;
            }
            for (byte[] key : keys.keys(mn)) {
                try {
                    byte[] plain = Hj212Crypto.decrypt(data, key);
                    if (Checksums.crc16Hj212(plain) == expectedCrc) {
                        return plain;
                    }
                } catch (RuntimeException ignored) {
                    // 这把密钥不对，试下一把
                }
            }
            return null;
        }

        private static String headerMn(byte[] data) {
            String text = new String(data, StandardCharsets.ISO_8859_1);
            int cp = text.indexOf("CP=&&");
            if (cp < 0) {
                return null;
            }
            for (String kv : text.substring(0, cp).split(";")) {
                if (kv.startsWith("MN=")) {
                    return kv.substring(3);
                }
            }
            return null;
        }

        private static void drop(ChannelHandlerContext ctx, ByteBuf frame, String reason) {
            CodecStats.increment("hj212.drop");
            // 212 是文本协议，日志里直接打印文本比十六进制好读
            String text = frame.toString(StandardCharsets.UTF_8).trim();
            log.warn("{} 丢弃一帧：{}，内容：{}", ctx.channel(), reason,
                    text.length() > 512 ? text.substring(0, 512) + "..." : text);
            if (log.isDebugEnabled()) {
                log.debug("十六进制：{}", HexUtils.toHex(frame));
            }
        }
    }

    /**
     * {@link Hj212Packet} → 一帧。设置了 {@link Hj212Packet#encryptWith(byte[])} 的按 2025 版加密 CP
     */
    @ChannelHandler.Sharable
    public static class Encoder extends MessageToByteEncoder<Hj212Packet> {

        @Override
        protected void encode(ChannelHandlerContext ctx, Hj212Packet msg, ByteBuf out) {
            out.writeBytes(Hj212Format.frameBytes(Hj212Format.format(msg), msg.getCipherKey()));
        }
    }
}
