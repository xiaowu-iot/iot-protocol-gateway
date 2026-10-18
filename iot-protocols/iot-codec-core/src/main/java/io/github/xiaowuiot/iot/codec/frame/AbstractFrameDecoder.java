package io.github.xiaowuiot.iot.codec.frame;

import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.codec.util.RateLimitedLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帧解码器基类。
 * <p>
 * TCP 是字节流，没有"包"的概念，一次 read 可能读到半帧，也可能读到好几帧。
 * {@link ByteToMessageDecoder} 已经帮我们把每次读到的数据累积起来，子类只需要做一件事：
 * <b>判断累积的数据里有没有一个完整的帧，有就切出来，没有就什么都不做、等下一次数据</b>。
 * <p>
 * 关于异常数据的处理，这里刻意<b>不抛异常</b>，而是丢弃后继续解析：
 * 抛异常会中断本次解码循环，同一次 read 里排在坏帧后面的好帧，要等到这个连接下次收到数据才会被解析。
 * 对于心跳间隔几分钟的终端来说，这意味着一条位置数据会被延迟几分钟。
 * 所以丢弃的数据统一交给 {@link #onDiscard} 处理，默认打 WARN 日志，需要统计或者断开连接的话可以重写它。
 */
public abstract class AbstractFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(AbstractFrameDecoder.class);

    private static final RateLimitedLog DISCARD_LOG = new RateLimitedLog(log, 1000);

    /**
     * 日志里最多打印的字节数，防止一大段垃圾数据把日志刷屏
     */
    private static final int MAX_LOG_BYTES = 256;

    /**
     * 跳过帧起始标记前面的无效数据。
     * <p>
     * 找不到起始标记时，末尾的 {@code marker.length - 1} 个字节先保留，
     * 因为它们可能是被拆到两次 read 里的起始标记的前半截。
     *
     * @return true 表示 readerIndex 已经停在起始标记上
     */
    protected boolean skipToMarker(ChannelHandlerContext ctx, ByteBuf in, byte[] marker) {
        int start = ByteBufUtils.indexOf(in, marker, in.readerIndex(), in.writerIndex());
        if (start >= 0) {
            if (start > in.readerIndex()) {
                discard(ctx, in, start - in.readerIndex(), DiscardReason.GARBAGE);
            }
            return true;
        }
        int keep = Math.min(in.readableBytes(), marker.length - 1);
        int garbage = in.readableBytes() - keep;
        if (garbage > 0) {
            discard(ctx, in, garbage, DiscardReason.GARBAGE);
        }
        return false;
    }

    /**
     * 从 readerIndex 开始丢弃 length 个字节。
     */
    protected void discard(ChannelHandlerContext ctx, ByteBuf in, int length, DiscardReason reason) {
        CodecStats.increment("frame." + reason.name());
        onDiscard(ctx, in.readSlice(length), reason);
    }

    /**
     * 数据被丢弃时回调。discarded 只在本方法内有效，需要保留的话自行拷贝。
     */
    protected void onDiscard(ChannelHandlerContext ctx, ByteBuf discarded, DiscardReason reason) {
        // 被扫描、被垃圾数据冲击时，每秒可能有成千上万次丢弃，日志要限流
        if (log.isWarnEnabled()) {
            DISCARD_LOG.warn("丢弃数据", String.format("%s %d 字节，原因：%s，数据：%s", ctx.channel(),
                    discarded.readableBytes(), reason.description(), preview(discarded)), null);
        }
    }

    private static String preview(ByteBuf buf) {
        if (buf.readableBytes() <= MAX_LOG_BYTES) {
            return HexUtils.toHex(buf);
        }
        return HexUtils.toHex(buf.slice(buf.readerIndex(), MAX_LOG_BYTES)) + "...";
    }
}
