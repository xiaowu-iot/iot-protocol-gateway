package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文本长度帧解码器，长度用十进制 ASCII 数字表示。典型代表是 HJ212：
 * <pre>
 *   ##  0101  QN=...;ST=32;CN=1062;...&amp;&amp;  1C80  \r\n
 *   前缀 长度   数据段（长度由前面 4 位决定）      CRC  后缀
 * </pre>
 * 输出的是<b>完整的一帧</b>（包含前缀和后缀），CRC 由协议模块验证。
 */
public class AsciiLengthFrameDecoder extends AbstractFrameDecoder {

    private final byte[] prefix;
    private final int lengthDigits;
    private final int checksumLength;
    private final byte[] suffix;
    private final int maxDataLength;

    /**
     * @param prefix         帧前缀，比如 {@code ##}
     * @param lengthDigits   长度字段的位数，比如 4
     * @param checksumLength 数据段之后、后缀之前的校验码长度，比如 4
     * @param suffix         帧后缀，比如 {@code \r\n}
     * @param maxDataLength  数据段最大长度
     */
    public AsciiLengthFrameDecoder(String prefix, int lengthDigits, int checksumLength, String suffix, int maxDataLength) {
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("帧前缀不能为空");
        }
        if (lengthDigits < 1 || lengthDigits > 9) {
            throw new IllegalArgumentException("长度字段只支持 1~9 位: " + lengthDigits);
        }
        this.prefix = prefix.getBytes(StandardCharsets.US_ASCII);
        this.lengthDigits = lengthDigits;
        this.checksumLength = checksumLength;
        this.suffix = suffix.getBytes(StandardCharsets.US_ASCII);
        this.maxDataLength = maxDataLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!skipToMarker(ctx, in, prefix)) {
            return;
        }
        int start = in.readerIndex();
        int headerLength = prefix.length + lengthDigits;
        if (in.readableBytes() < headerLength) {
            return;
        }

        int dataLength = parseDigits(in, start + prefix.length);
        if (dataLength < 0) {
            discard(ctx, in, 1, DiscardReason.BAD_LENGTH);
            return;
        }
        if (dataLength > maxDataLength) {
            discard(ctx, in, 1, DiscardReason.TOO_LONG);
            return;
        }
        int frameLength = headerLength + dataLength + checksumLength + suffix.length;
        if (in.readableBytes() < frameLength) {
            return;
        }
        // 后缀对不上，说明长度字段是错的，或者前缀是数据里碰巧出现的，跳过 1 个字节重新找
        if (!ByteBufUtils.matches(in, start + frameLength - suffix.length, suffix)) {
            discard(ctx, in, 1, DiscardReason.BAD_TRAILER);
            return;
        }
        out.add(in.readRetainedSlice(frameLength));
    }

    /**
     * 解析十进制长度，遇到非数字字符返回 -1。
     */
    private int parseDigits(ByteBuf in, int index) {
        int value = 0;
        for (int i = 0; i < lengthDigits; i++) {
            byte b = in.getByte(index + i);
            if (b < '0' || b > '9') {
                return -1;
            }
            value = value * 10 + (b - '0');
        }
        return value;
    }
}
