package io.github.xiaowuiot.iot.codec.frame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * 固定帧头 + 长度字段 的帧解码器，JT/T 1078 码流和大部分厂家私有协议都是这种格式：
 * <pre>
 *   [帧头][...][长度字段][...][数据体][校验/帧尾]
 *    ↑ header   ↑ lengthFieldOffset   ↑ 长度字段的值只包含数据体
 * </pre>
 * 帧长度 = lengthFieldOffset + lengthFieldLength + 长度字段的值 + lengthAdjustment。
 * 输出的是<b>完整的一帧</b>（包含帧头），方便协议模块解析帧头里的字段。
 * <p>
 * 和 Netty 自带的 {@code LengthFieldBasedFrameDecoder} 相比，多了两件事：
 * <ol>
 *     <li>按帧头重新同步：数据错位后会扫描下一个帧头，而不是把错位的数据当长度字段读，一错到底；</li>
 *     <li>长度字段位置可变：重写 {@link #lengthFieldOffset}，1078 就需要根据数据类型决定长度字段在哪。</li>
 * </ol>
 * 多字节整数一律按大端读取。
 */
public class HeaderLengthFrameDecoder extends AbstractFrameDecoder {

    private final byte[] header;
    private final int lengthFieldOffset;
    private final int lengthFieldLength;
    private final int lengthAdjustment;
    private final int maxFrameLength;

    /**
     * @param header            帧头
     * @param lengthFieldOffset 长度字段相对于帧头起始位置的偏移
     * @param lengthFieldLength 长度字段占几个字节，1~4
     * @param lengthAdjustment  数据体之后还有多少字节，比如 2 字节 CRC + 1 字节帧尾就填 3
     * @param maxFrameLength    最大帧长度
     */
    public HeaderLengthFrameDecoder(byte[] header, int lengthFieldOffset, int lengthFieldLength,
                                    int lengthAdjustment, int maxFrameLength) {
        if (header.length == 0) {
            throw new IllegalArgumentException("帧头不能为空");
        }
        if (lengthFieldLength < 1 || lengthFieldLength > 4) {
            throw new IllegalArgumentException("长度字段只支持 1~4 个字节: " + lengthFieldLength);
        }
        if (lengthFieldOffset < header.length) {
            throw new IllegalArgumentException("长度字段不能和帧头重叠");
        }
        this.header = header.clone();
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!skipToMarker(ctx, in, header)) {
            return;
        }
        int start = in.readerIndex();
        int offset = lengthFieldOffset(in, start);
        if (offset < 0) {
            return;
        }
        int lengthFieldEnd = offset + lengthFieldLength;
        if (in.readableBytes() < lengthFieldEnd) {
            return;
        }

        long frameLength = lengthFieldEnd + readLength(in, start + offset) + lengthAdjustment;
        if (frameLength < lengthFieldEnd || frameLength > maxFrameLength) {
            // 长度不合理，这个"帧头"多半是数据里碰巧出现的同样字节，跳过 1 个字节重新找帧头
            discard(ctx, in, 1, DiscardReason.BAD_LENGTH);
            return;
        }
        if (in.readableBytes() < frameLength) {
            return;
        }
        out.add(in.readRetainedSlice((int) frameLength));
    }

    /**
     * 返回长度字段相对帧起始位置的偏移，默认是构造时传入的固定值。
     * <p>
     * 长度字段位置不固定的协议重写这个方法；如果现有数据还不够判断，返回 -1，等更多数据到达。
     *
     * @param frameStart 帧头在 in 中的绝对下标
     */
    protected int lengthFieldOffset(ByteBuf in, int frameStart) {
        return lengthFieldOffset;
    }

    private long readLength(ByteBuf in, int index) {
        switch (lengthFieldLength) {
            case 1:
                return in.getUnsignedByte(index);
            case 2:
                return in.getUnsignedShort(index);
            case 3:
                return in.getUnsignedMedium(index);
            default:
                return in.getUnsignedInt(index);
        }
    }
}
