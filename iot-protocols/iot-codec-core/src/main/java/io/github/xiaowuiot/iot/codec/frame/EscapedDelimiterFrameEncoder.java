package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.util.ByteEscaper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * {@link EscapedDelimiterFrameDecoder} 的反向操作：转义帧内容，再在首尾加上分隔符。
 * <p>
 * 没有状态，可以在多个 Channel 之间共享一个实例。
 */
@ChannelHandler.Sharable
public class EscapedDelimiterFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    private final byte delimiter;
    private final ByteEscaper escaper;

    public EscapedDelimiterFrameEncoder(byte delimiter, ByteEscaper escaper) {
        this.delimiter = delimiter;
        this.escaper = escaper;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        out.writeByte(delimiter);
        escaper.escape(msg, out);
        out.writeByte(delimiter);
    }
}
