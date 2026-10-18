package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 分包合包，每条连接一个实例，只在这条连接的 IO 线程里使用，所以不需要加锁。
 * <p>
 * 按消息 ID 归组：同一条连接就是同一个终端，同一时间同一种消息只会有一组分包在传。
 * <p>
 * 必须考虑的异常情况：
 * <ul>
 *     <li>某一包永远不来：每组分包都有超时时间，超时后整组丢弃，否则内存只增不减</li>
 *     <li>同一包重复到达（终端没收到应答而重发）：覆盖，不重复计数</li>
 *     <li>总包数是个离谱的大数：超过上限直接丢弃，防止恶意或错误数据撑爆内存</li>
 * </ul>
 */
public class SubpackageAssembler {

    private final long timeoutMillis;
    private final int maxPackets;
    private final Map<Integer, Group> groups = new HashMap<>();

    public SubpackageAssembler(long timeoutMillis, int maxPackets) {
        this.timeoutMillis = timeoutMillis;
        this.maxPackets = maxPackets;
    }

    /**
     * 收到一包。
     *
     * @param body 这一包的消息体，方法内部会 retain，调用方照常释放自己的引用
     * @return 收齐后返回合并好的消息体（调用方负责释放），没收齐返回 null
     */
    public ByteBuf add(Jt808Header header, ByteBuf body, ByteBufAllocator alloc) {
        long now = System.currentTimeMillis();
        expire(now);

        int total = header.getTotalPackets();
        int no = header.getPacketNo();
        if (total <= 0 || total > maxPackets || no < 1 || no > total) {
            throw new IllegalArgumentException("非法的分包信息：" + no + "/" + total);
        }
        Group g = groups.get(header.getMsgId());
        if (g == null || g.parts.length != total) {
            if (g != null) {
                g.release();
            }
            g = new Group(total, now);
            groups.put(header.getMsgId(), g);
        }
        if (g.parts[no - 1] == null) {
            g.received++;
        } else {
            g.parts[no - 1].release();
        }
        g.parts[no - 1] = body.retain();
        g.lastTime = now;

        if (g.received < total) {
            return null;
        }
        groups.remove(header.getMsgId());
        CompositeByteBuf merged = alloc.compositeBuffer(total);
        for (ByteBuf part : g.parts) {
            merged.addComponent(true, part);
        }
        return merged;
    }

    /**
     * 当前还在等待的分包组数，监控用
     */
    public int pendingGroups() {
        return groups.size();
    }

    public void releaseAll() {
        for (Group g : groups.values()) {
            g.release();
        }
        groups.clear();
    }

    private void expire(long now) {
        Iterator<Group> it = groups.values().iterator();
        while (it.hasNext()) {
            Group g = it.next();
            if (now - g.lastTime > timeoutMillis) {
                g.release();
                it.remove();
            }
        }
    }

    private static final class Group {
        final ByteBuf[] parts;
        int received;
        long lastTime;

        Group(int total, long now) {
            parts = new ByteBuf[total];
            lastTime = now;
        }

        void release() {
            for (ByteBuf p : parts) {
                if (p != null) {
                    p.release();
                }
            }
        }
    }
}
