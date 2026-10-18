package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.frame.AbstractFrameDecoder;
import io.github.xiaowuiot.iot.codec.frame.DiscardReason;
import io.github.xiaowuiot.iot.codec.util.ByteEscaper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * 对照组：EscapedDelimiterFrameDecoder 改进前的写法，切出一帧后连结尾的分隔符一起吃掉。
 * 下一帧的起始 7E 丢了，或者设备让相邻两帧共用一个 7E 时，下一帧会被当成垃圾丢掉。
 */
class ConsumeEndDelimiterFrameDecoder extends AbstractFrameDecoder {

    private final byte[] delimiter;
    private final ByteEscaper escaper;
    private final int maxFrameLength;

    ConsumeEndDelimiterFrameDecoder(byte delimiter, ByteEscaper escaper, int maxFrameLength) {
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
            if (in.readableBytes() > maxFrameLength) {
                discard(ctx, in, in.readableBytes(), DiscardReason.TOO_LONG);
            }
            return;
        }
        if (end == start + 1) {
            in.skipBytes(1);
            return;
        }
        if (end - start + 1 > maxFrameLength) {
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
