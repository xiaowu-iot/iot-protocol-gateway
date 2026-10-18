package io.github.xiaowuiot.iot.slp.server;

import io.github.xiaowuiot.iot.codec.command.PendingRequests;
import io.github.xiaowuiot.iot.codec.server.TcpServer;
import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.RateLimitedLog;
import io.github.xiaowuiot.iot.slp.SlpMessage;
import io.github.xiaowuiot.iot.slp.body.SlpBodies;
import io.github.xiaowuiot.iot.slp.codec.SlpCodecs;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * SLP 接入服务。
 * <p>
 * 和 808 一样要先登录；和 808 不一样的是，SLP 是我们自己设计的协议，应答规则统一：
 * 命令加 0x80、流水号不变，所以指令应答匹配不需要像 808 那样按消息类型找"应答流水号"字段。
 */
public class SlpServer {

    public static final String PROTOCOL = "slp";

    private static final Logger log = LoggerFactory.getLogger(SlpServer.class);
    private static final RateLimitedLog FAILURE_LOG = new RateLimitedLog(log, 1000);

    private final SessionManager sessions;
    private final PendingRequests<SlpMessage> pending = new PendingRequests<>();
    private final TcpServer server;

    private SlpServer(Builder b) {
        this.sessions = b.sessions != null ? b.sessions : new SessionManager();
        Handler handler = new Handler(b.registered, b.heartbeatSeconds, b.listener);
        this.server = new TcpServer("SLP 接入服务", PROTOCOL, b.port, b.heartbeatSeconds * 3, b.workerThreads,
                pipeline -> {
                    SlpCodecs.addTo(pipeline);
                    pipeline.addLast("handler", handler);
                },
                () -> sessions.closeAll(PROTOCOL));
    }

    public static Builder builder() {
        return new Builder();
    }

    public void start() throws InterruptedException {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    /**
     * 单元测试用：把流水线装到 EmbeddedChannel 上
     */
    public void initPipeline(ChannelPipeline pipeline) {
        server.initPipeline(pipeline);
    }

    /**
     * 网络层统计、原始报文记录
     */
    public TcpServer getTcpServer() {
        return server;
    }

    public SessionManager getSessions() {
        return sessions;
    }

    /**
     * 下发指令并等待应答。
     */
    public CompletableFuture<SlpMessage> send(String deviceId, int command, Object body, long timeout, TimeUnit unit) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive() || !PROTOCOL.equals(session.getProtocol())) {
            CompletableFuture<SlpMessage> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("设备不在线：" + deviceId));
            return f;
        }
        int serial = session.nextSerialNo();
        CompletableFuture<SlpMessage> future = pending.register(deviceId, serial, timeout, unit);
        session.write(new SlpMessage(command, serial, Long.parseLong(deviceId), body)).addListener(f -> {
            if (!f.isSuccess()) {
                pending.fail(deviceId, serial, f.cause());
            }
        });
        return future;
    }

    /**
     * 业务消息：格口状态等。
     * 在 future 完成后才回应答。
     */
    public interface Listener {
        Listener NOOP = (session, msg) -> CompletableFuture.completedFuture(null);

        CompletionStage<Void> onMessage(Session session, SlpMessage msg);
    }

    @ChannelHandler.Sharable
    class Handler extends SimpleChannelInboundHandler<SlpMessage> {

        private final LongPredicate registered;
        private final int heartbeatSeconds;
        private final Listener listener;

        Handler(LongPredicate registered, int heartbeatSeconds, Listener listener) {
            this.registered = registered;
            this.heartbeatSeconds = heartbeatSeconds;
            this.listener = listener;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SlpMessage msg) {
            Session session = SessionManager.get(ctx.channel());
            if (msg.getCommand() == SlpMessage.LOGIN) {
                onLogin(ctx, msg);
                return;
            }
            if (session == null) {
                log.warn("{} 未登录，丢弃 {}", ctx.channel(), msg);
                return;
            }
            session.touch();
            if (msg.isResponse()) {
                // 设备对平台指令的应答：命令 | 0x80，流水号和请求相同
                pending.complete(session.getDeviceId(), msg.getSerialNo(), msg);
                return;
            }
            switch (msg.getCommand()) {
                case SlpMessage.HEARTBEAT:
                    ctx.writeAndFlush(msg.response(null));
                    break;
                case SlpMessage.OTA_CHUNK_REQUEST:
                    // 开源版不带固件分发，统一回"版本不存在"
                    ctx.writeAndFlush(msg.response(new SlpBodies.OtaChunk(SlpBodies.OtaChunk.VERSION_NOT_FOUND,
                            msg.<SlpBodies.OtaChunkRequest>getBody().getOffset(), new byte[0])));
                    break;
                default:
                    listener.onMessage(session, msg).whenComplete((v, e) -> {
                        if (e == null) {
                            ctx.writeAndFlush(msg.response(new SlpBodies.Result(SlpBodies.Result.SUCCESS)));
                        } else {
                            FAILURE_LOG.warn("处理失败，不回应答", session + " " + msg, e);
                        }
                    });
            }
        }

        private void onLogin(ChannelHandlerContext ctx, SlpMessage msg) {
            long now = System.currentTimeMillis() / 1000;
            int result;
            if (msg.getVersion() != SlpMessage.VERSION_1) {
                result = SlpBodies.LoginAck.VERSION_UNSUPPORTED;
            } else if (!registered.test(msg.getDeviceId())) {
                result = SlpBodies.LoginAck.NOT_REGISTERED;
            } else {
                result = SlpBodies.LoginAck.SUCCESS;
                Session session = sessions.register(String.valueOf(msg.getDeviceId()), PROTOCOL, ctx.channel());
                SlpBodies.Login login = msg.getBody();
                session.setAttribute("firmwareVersion", login.getFirmwareVersion());
                session.setAttribute("doorCount", login.getDoorCount());
            }
            log.info("{} 设备 {} 登录 {}，结果 {}", ctx.channel(), msg.getDeviceId(), msg.getBody(), result);
            ctx.writeAndFlush(msg.response(new SlpBodies.LoginAck(result, now, heartbeatSeconds)));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            sessions.unregister(ctx.channel());
            ctx.fireChannelInactive();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                log.info("{} 长时间没有收到数据，判定离线", ctx.channel());
                ctx.close();
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.info("{} 连接异常，关闭：{}", ctx.channel(), cause.toString());
            ctx.close();
        }
    }

    public static class Builder {
        private int port = 7614;
        private int heartbeatSeconds = 60;
        private int workerThreads = 0;
        private SessionManager sessions;
        private LongPredicate registered = id -> true;
        private Listener listener = Listener.NOOP;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 登录应答里下发给设备的心跳间隔，空闲超时自动取它的 3 倍
         */
        public Builder heartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder sessions(SessionManager sessions) {
            this.sessions = sessions;
            return this;
        }

        /**
         * 设备是否已在平台登记，默认全部放行
         */
        public Builder registered(LongPredicate registered) {
            this.registered = registered;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public SlpServer build() {
            return new SlpServer(this);
        }
    }
}
