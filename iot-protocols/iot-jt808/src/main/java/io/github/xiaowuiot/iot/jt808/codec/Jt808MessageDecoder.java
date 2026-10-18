package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.body.BodyDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 帧 → {@link Jt808Message}。
 * <p>
 * 输入是帧解码器切好、已经还原了转义的一帧：消息头 + 消息体 + 校验码。按顺序做这几件事：
 * <ol>
 *     <li>校验 BCC</li>
 *     <li>解析消息头，bit14 自动区分 2013 / 2019 版</li>
 *     <li>核对消息体长度</li>
 *     <li>分包的先缓存，收齐后合并</li>
 *     <li>按消息 ID 找到消息体解码器</li>
 * </ol>
 * 任何一步失败都<b>丢弃这一帧、打日志，不抛异常、不断开连接</b>：一帧坏数据不应该让设备掉线。
 * <p>
 * 有状态（分包缓存），每条连接 new 一个。
 */
public class Jt808MessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(Jt808MessageDecoder.class);

    /**
     * 最短的一帧：2013 版消息头 12 字节 + 校验码 1 字节
     */
    private static final int MIN_FRAME_LENGTH = 13;

    private final SubpackageAssembler assembler;

    public Jt808MessageDecoder() {
        this(new SubpackageAssembler(TimeUnit.SECONDS.toMillis(60), 4096));
    }

    public Jt808MessageDecoder(SubpackageAssembler assembler) {
        this.assembler = assembler;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        if (frame.readableBytes() < MIN_FRAME_LENGTH) {
            drop(ctx, frame, "长度不足");
            return;
        }
        int start = frame.readerIndex();
        int checkIndex = frame.writerIndex() - 1;
        int expected = frame.getUnsignedByte(checkIndex);
        int actual = Checksums.bcc(frame, start, checkIndex - start);
        if (expected != actual) {
            drop(ctx, frame, String.format("校验失败，报文中是 %02X，计算结果是 %02X", expected, actual));
            return;
        }

        Jt808Header header;
        try {
            header = Jt808Header.read(frame);
        } catch (IndexOutOfBoundsException e) {
            drop(ctx, frame.readerIndex(start), "消息头不完整");
            return;
        }
        int bodyLength = checkIndex - frame.readerIndex();
        if (bodyLength != header.getBodyLength()) {
            drop(ctx, frame.readerIndex(start), "消息体长度不符，属性里是 " + header.getBodyLength() + "，实际 " + bodyLength);
            return;
        }
        if (header.getEncryption() != 0) {
            drop(ctx, frame.readerIndex(start), "不支持加密的消息");
            return;
        }
        ByteBuf body = frame.slice(frame.readerIndex(), bodyLength);

        if (header.isSubpackage()) {
            ByteBuf merged;
            try {
                merged = assembler.add(header, body, ctx.alloc());
            } catch (IllegalArgumentException e) {
                drop(ctx, frame.readerIndex(start), e.getMessage());
                return;
            }
            if (merged == null) {
                out.add(Jt808Message.fragment(header));
                return;
            }
            try {
                header.setBodyLength(merged.readableBytes());
                decodeBody(ctx, header, merged, out);
            } finally {
                merged.release();
            }
            return;
        }
        decodeBody(ctx, header, body, out);
    }

    private void decodeBody(ChannelHandlerContext ctx, Jt808Header header, ByteBuf body, List<Object> out) {
        BodyDecoder<?> decoder = BodyDecoders.get(header.getMsgId());
        if (decoder == null) {
            out.add(new Jt808Message(header, ByteBufUtil.getBytes(body)));
            return;
        }
        int bodyStart = body.readerIndex();
        try {
            out.add(new Jt808Message(header, decoder.decode(body, header)));
        } catch (RuntimeException e) {
            // 消息体字段和协议不符，多半是厂家固件和文档不一致，原始报文一定要打出来
            CodecStats.increment("jt808.body");
            log.warn("{} 消息体解析失败 {}，消息体：{}", ctx.channel(), header,
                    HexUtils.toHex(body.readerIndex(bodyStart)), e);
        }
    }

    private static void drop(ChannelHandlerContext ctx, ByteBuf frame, String reason) {
        CodecStats.increment("jt808.drop");
        log.warn("{} 丢弃一帧：{}，内容：{}", ctx.channel(), reason, HexUtils.toHex(frame));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        assembler.releaseAll();
    }
}
