package io.github.xiaowuiot.iot.simulator.slp;

import io.github.xiaowuiot.iot.simulator.SimulatorStats;
import io.github.xiaowuiot.protocol.slp.SlpMessage;
import io.github.xiaowuiot.protocol.slp.body.SlpBodies;
import io.github.xiaowuiot.protocol.slp.codec.SlpCodecs;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 一台模拟储物柜。
 */
@ChannelHandler.Sharable
public class SlpDevice extends SimpleChannelInboundHandler<SlpMessage> {

    private static final Logger log = LoggerFactory.getLogger(SlpDevice.class);

    private final long deviceId;
    private final int doorCount;
    private final Bootstrap bootstrap;
    private final InetSocketAddress remote;
    private final SimulatorStats stats;
    private final boolean verbose;

    private final int[] doors;
    private String firmwareVersion = "1.0.0";
    private Channel channel;
    private int serialNo;
    private boolean online;
    private volatile boolean stopped;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> statusTask;

    public SlpDevice(long deviceId, int doorCount, Bootstrap bootstrap, InetSocketAddress remote, SimulatorStats stats,
                     boolean verbose) {
        this.deviceId = deviceId;
        this.doorCount = doorCount;
        this.bootstrap = bootstrap;
        this.remote = remote;
        this.stats = stats;
        this.verbose = verbose;
        this.doors = new int[doorCount + 1];
    }

    public void connect() {
        if (stopped) {
            return;
        }
        SlpDevice self = this;
        bootstrap.clone().handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                SlpCodecs.addTo(ch.pipeline());
                ch.pipeline().addLast(self);
            }
        }).connect(remote).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                stats.connectFailures.increment();
                reconnectLater(f.channel());
            }
        });
    }

    public void stop() {
        stopped = true;
        if (channel != null) {
            channel.close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        stats.connected.incrementAndGet();
        send(SlpMessage.LOGIN, new SlpBodies.Login(firmwareVersion, doorCount));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stats.connected.decrementAndGet();
        stats.disconnects.increment();
        if (online) {
            online = false;
            stats.online.decrementAndGet();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (statusTask != null) {
            statusTask.cancel(false);
        }
        reconnectLater(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SlpMessage msg) {
        if (verbose) {
            log.info("{} 收到 {}", deviceId, msg);
        }
        if (msg.isResponse()) {
            stats.responses.increment();
        }
        switch (msg.getCommand()) {
            case SlpMessage.LOGIN | 0x80 -> onLoginAck(msg.getBody());
            case SlpMessage.OPEN_DOOR -> onOpenDoor(msg);
            // 开源版不带升级流程，收到升级通知直接回"不升级"
            case SlpMessage.OTA_NOTIFY -> channel.writeAndFlush(msg.response(new SlpBodies.Result(1)));
            default -> {
            }
        }
    }

    private void onLoginAck(SlpBodies.LoginAck ack) {
        if (ack.getResult() != SlpBodies.LoginAck.SUCCESS) {
            log.warn("{} 登录失败 {}", deviceId, ack.getResult());
            return;
        }
        online = true;
        stats.online.incrementAndGet();
        int hb = ack.getHeartbeatSeconds();
        heartbeatTask = channel.eventLoop().scheduleAtFixedRate(() -> send(SlpMessage.HEARTBEAT, null), hb, hb, TimeUnit.SECONDS);
        // 每 60 秒随机有人存取一件物品
        statusTask = channel.eventLoop().scheduleAtFixedRate(this::randomActivity,
                ThreadLocalRandom.current().nextLong(60_000), 60_000, TimeUnit.MILLISECONDS);
        reportDoors(allDoors());
    }

    private void onOpenDoor(SlpMessage msg) {
        stats.commands.increment();
        SlpBodies.OpenDoor cmd = msg.getBody();
        int door = cmd.getDoor();
        int result;
        if (door < 1 || door > doorCount) {
            result = 1;
        } else if ((doors[door] & SlpBodies.DoorStatus.OPEN) != 0) {
            result = 3;
        } else {
            result = 0;
            doors[door] |= SlpBodies.DoorStatus.OPEN;
        }
        log.info("{} 开门 {}，订单 {}，结果 {}", deviceId, door, cmd.getOrderNo(), result);
        channel.writeAndFlush(msg.response(new SlpBodies.Result(result)));
        if (result == 0) {
            reportDoors(List.of(new int[]{door, doors[door]}));
            // 5 秒后取走物品、关门
            channel.eventLoop().schedule(() -> {
                doors[door] = 0;
                reportDoors(List.of(new int[]{door, 0}));
            }, 5, TimeUnit.SECONDS);
        }
    }

    // ==================== 其他 ====================

    private void randomActivity() {
        int door = ThreadLocalRandom.current().nextInt(1, doorCount + 1);
        doors[door] ^= SlpBodies.DoorStatus.OCCUPIED;
        reportDoors(List.of(new int[]{door, doors[door]}));
    }

    private List<int[]> allDoors() {
        List<int[]> list = new ArrayList<>(doorCount);
        for (int i = 1; i <= doorCount; i++) {
            list.add(new int[]{i, doors[i]});
        }
        return list;
    }

    private void reportDoors(List<int[]> changed) {
        send(SlpMessage.DOOR_STATUS, new SlpBodies.DoorStatus(System.currentTimeMillis() / 1000, changed));
    }

    private void send(int command, Object body) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        stats.sent.increment();
        channel.writeAndFlush(new SlpMessage(command, serialNo++ & 0xFFFF, deviceId, body));
    }

    private void reconnectLater(Channel ch) {
        if (!stopped) {
            ch.eventLoop().schedule(this::connect, ThreadLocalRandom.current().nextLong(1000, 3000), TimeUnit.MILLISECONDS);
        }
    }
}
