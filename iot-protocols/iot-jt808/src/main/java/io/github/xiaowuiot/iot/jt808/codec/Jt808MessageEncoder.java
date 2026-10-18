package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.body.Jt808Body;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * {@link Jt808Message} → 消息头 + 消息体 + 校验码。
 * <p>
 * 输出的还没有转义、没有首尾 7E，由后面的 {@code EscapedDelimiterFrameEncoder} 完成。
 * 注意顺序：<b>先算校验，再转义</b>，和解码时正好相反。
 * <p>
 * 消息体长度在编码完消息体之后回填，调用方不需要自己算。无状态，可以共享。
 */
@ChannelHandler.Sharable
public class Jt808MessageEncoder extends MessageToByteEncoder<Jt808Message> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Jt808Message msg, ByteBuf out) {
        encode(msg, out);
    }

    public static void encode(Jt808Message msg, ByteBuf out) {
        Jt808Header header = msg.getHeader();
        int start = out.writerIndex();
        header.setBodyLength(0);
        header.write(out);
        int bodyStart = out.writerIndex();

        Object body = msg.getBody();
        if (body instanceof Jt808Body) {
            ((Jt808Body) body).encode(out, header);
        } else if (body instanceof byte[]) {
            out.writeBytes((byte[]) body);
        } else if (body != null) {
            throw new IllegalArgumentException("不支持的消息体类型：" + body.getClass());
        }

        int bodyLength = out.writerIndex() - bodyStart;
        header.setBodyLength(bodyLength);
        if (bodyLength > Jt808Header.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(String.format("0x%04X 消息体 %d 字节，超过 1023 字节，需要分包发送",
                    header.getMsgId(), bodyLength));
        }
        // 回填消息体属性里的长度：低 10 位
        int attr = out.getUnsignedShort(start + 2);
        out.setShort(start + 2, (attr & ~Jt808Header.MAX_BODY_LENGTH) | bodyLength);
        out.writeByte(Checksums.bcc(out, start, out.writerIndex() - start));
    }
}
