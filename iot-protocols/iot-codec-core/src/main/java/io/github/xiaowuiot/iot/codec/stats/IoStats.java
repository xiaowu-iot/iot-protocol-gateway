package io.github.xiaowuiot.iot.codec.stats;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一个接入服务的网络层统计：连接数、收发字节数。
 * <p>
 * 放在流水线最前面，看到的是原始字节。所有连接共用一个实例，计数用 {@link LongAdder}，几十个 IO 线程同时累加也不会互相争抢。
 */
@ChannelHandler.Sharable
public class IoStats extends ChannelDuplexHandler {

    private final AtomicInteger connections = new AtomicInteger();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder readsIn = new LongAdder();

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connections.incrementAndGet();
        accepted.increment();
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connections.decrementAndGet();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            bytesIn.add(((ByteBuf) msg).readableBytes());
            readsIn.increment();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            bytesOut.add(((ByteBuf) msg).readableBytes());
        }
        ctx.write(msg, promise);
    }

    /**
     * 当前 TCP 连接数，包括还没鉴权的
     */
    public int connections() {
        return connections.get();
    }

    public long accepted() {
        return accepted.sum();
    }

    public long bytesIn() {
        return bytesIn.sum();
    }

    public long bytesOut() {
        return bytesOut.sum();
    }

    /**
     * 读事件次数。和收到的帧数对比，可以看出拆包粘包的程度
     */
    public long readsIn() {
        return readsIn.sum();
    }
}
