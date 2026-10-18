package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.ByteEscaper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * 分隔符 + 转义 的帧解码器，典型代表是 JT/T 808：
 * <pre>
 *   7E [消息头 + 消息体 + 校验码（转义后）] 7E
 * </pre>
 * 输出的是<b>去掉首尾分隔符、并且已经转义还原</b>的帧内容。校验码由协议模块负责验证，这里只管切帧。
 * <p>
 * 为什么不直接用 Netty 的 {@code DelimiterBasedFrameDecoder}？它假设每帧只有结尾一个分隔符，
 * 而 808 每帧首尾各一个，两帧之间会出现 {@code 7E 7E}，用它会切出很多空帧，而且它也不管转义。
 */
public class EscapedDelimiterFrameDecoder extends AbstractFrameDecoder {

    private final byte[] delimiter;
    private final ByteEscaper escaper;
    private final int maxFrameLength;

    /**
     * @param delimiter      帧分隔符
     * @param escaper        转义规则
     * @param maxFrameLength 最大帧长度，按线路上的原始字节算（包含首尾分隔符、转义前）
     */
    public EscapedDelimiterFrameDecoder(byte delimiter, ByteEscaper escaper, int maxFrameLength) {
        this.delimiter = new byte[]{delimiter};
        this.escaper = escaper;
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!skipToMarker(ctx, in, delimiter)) {
            return;
        }
        int start = in.readerIndex();
        int end = in.indexOf(start + 1, in.writerIndex(), delimiter[0]);

        if (end < 0) {
            // 结束分隔符还没到，数据又已经超长，说明这一帧废了
            if (in.readableBytes() > maxFrameLength) {
                discard(ctx, in, in.readableBytes(), DiscardReason.TOO_LONG);
            }
            return;
        }
        if (end == start + 1) {
            // 连续两个分隔符：前一个是上一帧的结尾（或者上一帧只收到了后半截），从后一个开始算新帧
            in.skipBytes(1);
            return;
        }
        if (end - start + 1 > maxFrameLength) {
            // 只丢到 end 之前，end 本身可能是下一帧的起始分隔符
            discard(ctx, in, end - start, DiscardReason.TOO_LONG);
            return;
        }

        ByteBuf frame = ctx.alloc().buffer(end - start - 1);
        if (!escaper.unescape(in, start + 1, end - start - 1, frame)) {
            frame.release();
            discard(ctx, in, end - start, DiscardReason.BAD_ESCAPE);
            return;
        }
        in.readerIndex(end + 1);
        out.add(frame);
    }
}
