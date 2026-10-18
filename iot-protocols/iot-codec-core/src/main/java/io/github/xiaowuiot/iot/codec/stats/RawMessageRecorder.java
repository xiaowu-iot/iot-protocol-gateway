package io.github.xiaowuiot.iot.codec.stats;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按设备记录原始报文：排查"这台设备到底发了什么"的第一手资料。
 * <p>
 * 放在流水线最前面（帧解码器之前），记录的是<b>每一次 TCP 读写的原始字节</b>，而不是切好的帧：
 * 拆包粘包、垃圾数据、半截帧都原样保留，排查解码问题时这才是真相。
 * <p>
 * 两个去处：
 * <ul>
 *     <li>内存：每台设备最近 {@code perDevice} 条，管理接口直接查，不用登服务器翻日志</li>
 *     <li>日志：logger 名为 {@code iot.raw}，按天滚动保存，用来查更早的数据。
 *     全量记录的话日志量很大，默认只记录 {@link #trace} 过的设备，或者把 iot.raw 的级别调到 DEBUG 记录全部</li>
 * </ul>
 * 设备鉴权之前还不知道设备号，这段时间的报文先暂存在连接上，鉴权成功后归到设备名下。
 */
@ChannelHandler.Sharable
public class RawMessageRecorder extends ChannelDuplexHandler {

    private static final Logger rawLog = LoggerFactory.getLogger("iot.raw");

    private static final AttributeKey<Deque<Record>> PENDING = AttributeKey.valueOf("iot.raw.pending");

    /**
     * 鉴权之前最多暂存多少条，防止有人连上来一直发垃圾数据
     */
    private static final int MAX_PENDING = 20;

    /**
     * 单条记录最多保存多少字节
     */
    private static final int MAX_BYTES = 2048;

    public static final class Record {
        public final long time;
        public final String direction;
        public final String remote;
        public final String hex;

        Record(long time, String direction, String remote, String hex) {
            this.time = time;
            this.direction = direction;
            this.remote = remote;
            this.hex = hex;
        }
    }

    private final String protocol;
    private final int perDevice;
    private final Map<String, Deque<Record>> byDevice;
    private final Set<String> traced = ConcurrentHashMap.newKeySet();

    /**
     * @param perDevice  每台设备在内存里保留多少条
     * @param maxDevices 内存里最多保留多少台设备，超过后淘汰最久没有数据的
     */
    public RawMessageRecorder(String protocol, int perDevice, int maxDevices) {
        this.protocol = protocol;
        this.perDevice = perDevice;
        this.byDevice = Collections.synchronizedMap(new LinkedHashMap<String, Deque<Record>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Record>> eldest) {
                return size() > maxDevices;
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            record(ctx.channel(), "IN", (ByteBuf) msg);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            record(ctx.channel(), "OUT", (ByteBuf) msg);
        }
        ctx.write(msg, promise);
    }

    private void record(Channel ch, String direction, ByteBuf buf) {
        String hex = buf.readableBytes() <= MAX_BYTES ? HexUtils.toHex(buf)
                : HexUtils.toHex(buf.slice(buf.readerIndex(), MAX_BYTES)) + "...";
        Record r = new Record(System.currentTimeMillis(), direction, String.valueOf(ch.remoteAddress()), hex);
        Session session = ch.attr(Session.KEY).get();
        if (session == null) {
            // 还不知道是哪台设备，先存在连接上
            Deque<Record> pending = ch.attr(PENDING).get();
            if (pending == null) {
                pending = new ArrayDeque<>();
                ch.attr(PENDING).set(pending);
            }
            if (pending.size() >= MAX_PENDING) {
                pending.pollFirst();
            }
            pending.addLast(r);
            if (rawLog.isDebugEnabled()) {
                rawLog.debug("{} {} {} {} {}", protocol, "-", direction, r.remote, hex);
            }
            return;
        }
        String deviceId = session.getDeviceId();
        Deque<Record> pending = ch.attr(PENDING).getAndSet(null);
        Deque<Record> ring = byDevice.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
        synchronized (ring) {
            if (pending != null) {
                for (Record p : pending) {
                    add(ring, p);
                }
            }
            add(ring, r);
        }
        if (traced.contains(deviceId) || rawLog.isDebugEnabled()) {
            rawLog.info("{} {} {} {} {}", protocol, deviceId, direction, r.remote, hex);
        }
    }

    private void add(Deque<Record> ring, Record r) {
        if (ring.size() >= perDevice) {
            ring.pollFirst();
        }
        ring.addLast(r);
    }

    /**
     * 查询某台设备在时间范围内的原始报文（只限内存里还保留的）
     */
    public List<Record> query(String deviceId, long from, long to) {
        Deque<Record> ring = byDevice.get(deviceId);
        if (ring == null) {
            return Collections.emptyList();
        }
        List<Record> list = new ArrayList<>();
        synchronized (ring) {
            for (Record r : ring) {
                if (r.time >= from && r.time <= to) {
                    list.add(r);
                }
            }
        }
        return list;
    }

    /**
     * 开始把这台设备的每一条报文都写进 iot.raw 日志，对接新设备、排查问题时打开
     */
    public void trace(String deviceId, boolean on) {
        if (on) {
            traced.add(deviceId);
        } else {
            traced.remove(deviceId);
        }
    }

    public Set<String> traced() {
        return Collections.unmodifiableSet(traced);
    }

    public String getProtocol() {
        return protocol;
    }
}
