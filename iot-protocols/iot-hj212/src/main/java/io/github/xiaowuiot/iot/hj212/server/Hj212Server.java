package io.github.xiaowuiot.iot.hj212.server;

import io.github.xiaowuiot.iot.codec.server.TcpServer;
import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.RateLimitedLog;
import io.github.xiaowuiot.iot.hj212.Hj212Commands;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.github.xiaowuiot.iot.hj212.Hj212Split;
import io.github.xiaowuiot.iot.hj212.codec.Hj212Codecs;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;

/**
 * 212 接入服务。
 * <p>
 * 和 808 最大的区别：<b>212 没有注册、鉴权这一步</b>，数采仪连上来直接发数据，每条数据里都带着 MN 和密码 PW。
 * 所以收到第一条合法的数据时创建会话，密码不对的数据直接丢弃。
 */
public class Hj212Server {

    public static final String PROTOCOL = "hj212";

    private static final Logger log = LoggerFactory.getLogger(Hj212Server.class);
    private static final RateLimitedLog FAILURE_LOG = new RateLimitedLog(log, 1000);

    private final SessionManager sessions;
    private final TcpServer server;
    private final Hj212CommandSender commandSender;

    private Hj212Server(Builder b) {
        this.sessions = b.sessions != null ? b.sessions : new SessionManager();
        this.commandSender = new Hj212CommandSender(sessions);
        Handler handler = new Handler(sessions, b.passwordChecker, b.listener, commandSender, b.autoTimeSync);
        this.server = new TcpServer("212 接入服务", PROTOCOL, b.port, b.idleSeconds, b.workerThreads,
                pipeline -> {
                    Hj212Codecs.addTo(pipeline, b.keys);
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
     * 网络层统计、原始报文记录
     */
    public TcpServer getTcpServer() {
        return server;
    }

    public SessionManager getSessions() {
        return sessions;
    }

    public Hj212CommandSender getCommandSender() {
        return commandSender;
    }

    /**
     * 业务数据交给谁处理。和 808 一样：future 完成后才回应答。
     */
    @FunctionalInterface
    public interface Listener {
        Listener NOOP = (session, packet) -> CompletableFuture.completedFuture(null);

        CompletionStage<Void> onPacket(Session session, Hj212Packet packet);
    }

    @ChannelHandler.Sharable
    public static class Handler extends SimpleChannelInboundHandler<Hj212Packet> {

        private static final DateTimeFormatter SYSTEM_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        private final SessionManager sessions;
        private final BiPredicate<String, String> passwordChecker;
        private final Listener listener;
        private final Hj212CommandSender commandSender;
        private final Hj212Split.Assembler assembler = new Hj212Split.Assembler(60_000);
        private final boolean autoTimeSync;
        private static final io.netty.util.AttributeKey<String> PW_FAILED = io.netty.util.AttributeKey.valueOf("hj212.pwFailed");

        public Handler(SessionManager sessions, BiPredicate<String, String> passwordChecker, Listener listener) {
            this(sessions, passwordChecker, listener, new Hj212CommandSender(sessions), false);
        }

        public Handler(SessionManager sessions, BiPredicate<String, String> passwordChecker, Listener listener,
                       Hj212CommandSender commandSender, boolean autoTimeSync) {
            this.sessions = sessions;
            this.passwordChecker = passwordChecker;
            this.listener = listener;
            this.commandSender = commandSender;
            this.autoTimeSync = autoTimeSync;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Hj212Packet received) {
            if (!passwordChecker.test(received.getMn(), received.getPw())) {
                log.warn("{} 设备 {} 密码错误，丢弃", ctx.channel(), received.getMn());
                // 现场机每条数据都带密码，密码错了每条都会失败：同一条连接上同一个 MN 只记一次
                if (!java.util.Objects.equals(received.getMn(), ctx.channel().attr(PW_FAILED).getAndSet(received.getMn()))) {
                    sessions.authResult(PROTOCOL, ctx.channel(), received.getMn(), "密码校验", false, "密码错误");
                }
                return;
            }
            Session session = SessionManager.get(ctx.channel());
            if (session == null || !session.getDeviceId().equals(received.getMn())) {
                // 第一条合法数据到达时上线。一条连接上的 MN 变了（数采仪换了配置），旧设备下线，按新设备处理
                if (session != null) {
                    sessions.unregister(ctx.channel());
                }
                sessions.authResult(PROTOCOL, ctx.channel(), received.getMn(), "密码校验", true, null);
                session = sessions.register(received.getMn(), PROTOCOL, ctx.channel());
            }
            session.touch();
            // 平台下发命令时要用和设备一样的 ST、PW。不能只从数据包里取：现场机上线后第一条可能是 1013 校时请求，
            // 这时还没有任何数据包，平台用空密码下发 1012，现场机会回"PW 错误"
            if (received.getPw() != null) {
                session.setAttribute(Hj212CommandSender.ATTR_PW, received.getPw());
            }
            session.setAttribute(Hj212CommandSender.ATTR_VERSION, received.version());
            if (received.getSt() != null && !Hj212Packet.ST_SYSTEM.equals(received.getSt())) {
                session.setAttribute(Hj212CommandSender.ATTR_ST, received.getSt());
            }
            if (received.isSplit() && received.needAck() && Hj212Commands.isData(received.getCn())) {
                // 分包的每一包都要应答，否则现场机会重发这一包
                ctx.writeAndFlush(received.dataAck());
            }
            Hj212Packet p = assembler.add(received);
            if (p == null) {
                return;
            }
            if (commandSender.onPacket(p)) {
                if (!Hj212Commands.isData(p.getCn())) {
                    // 9011 / 9012 / 9013，以及参数命令的返回数据（比如 1011 的现场机时间）：交给等待方就够了
                    return;
                }
                // 平台提取的历史数据：也交给业务处理（补进数据库），但不回 9014
                p.markRequested();
            }
            if (Hj212Commands.HEARTBEAT.equals(p.getCn())) {
                // 2025 版心跳包（表 C.54）：会话已经刷新过了，要求应答的回 9013，不往下游送
                if (p.needAck()) {
                    ctx.writeAndFlush(p.reply(Hj212Commands.NOTIFY_ACK));
                }
                return;
            }
            if (Hj212Commands.TIME_CALIBRATION_REQ.equals(p.getCn())) {
                onTimeCalibration(ctx, p);
                return;
            }
            Hj212Packet packet = p;
            listener.onPacket(session, packet).whenComplete((v, e) -> {
                if (e != null) {
                    FAILURE_LOG.warn("处理失败，不回应答", ctx.channel() + " " + packet.getMn(), e);
                    return;
                }
                // 只有 Flag 要求应答的才回，回多了有的数采仪会当成指令，报错；分包的已经逐包应答过了
                if (packet.needAck() && !received.isSplit() && !packet.isRequested() && Hj212Commands.isData(packet.getCn())) {
                    ctx.writeAndFlush(packet.dataAck());
                }
            });
        }

        /**
         * 现场机请求校时（1013）：先回通知应答，再用 1012 把平台的时间设置给它。
         * 现场机的时钟会漂，一个月差几分钟很常见；数据时间不准，小时均值、日均值就会算到错误的时段里
         */
        private void onTimeCalibration(ChannelHandlerContext ctx, Hj212Packet p) {
            if (p.needAck()) {
                ctx.writeAndFlush(p.reply(Hj212Commands.NOTIFY_ACK));
            }
            if (!autoTimeSync) {
                return;
            }
            String now = LocalDateTime.now(ZoneOffset.ofHours(8)).format(SYSTEM_TIME);
            log.info("{} 现场机请求校时，下发平台时间 {}", p.getMn(), now);
            commandSender.send(p.getMn(), Hj212Commands.SET_TIME, Collections.singletonMap("SystemTime", now), 10)
                    .whenComplete((r, e) -> log.info("{} 校时结果：{}", p.getMn(), e == null ? r : e.toString()));
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            sessions.connected(PROTOCOL, ctx.channel());
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            sessions.disconnected(PROTOCOL, ctx.channel());
            ctx.fireChannelInactive();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                log.info("{} 长时间没有收到数据，判定离线", ctx.channel());
                SessionManager.closeReason(ctx.channel(), "心跳超时");
                ctx.close();
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.info("{} 连接异常，关闭：{}", ctx.channel(), cause.toString());
            SessionManager.closeReason(ctx.channel(), "连接异常：" + cause.getMessage());
            ctx.close();
        }
    }

    public static class Builder {
        private int port = 7613;

        /**
         * 212 的分钟数据通常 1~10 分钟一条，空闲超时默认 15 分钟
         */
        private int idleSeconds = 900;
        private int workerThreads = 0;
        private SessionManager sessions;
        private BiPredicate<String, String> passwordChecker = (mn, pw) -> true;
        private Listener listener = Listener.NOOP;
        private boolean autoTimeSync = true;
        private Hj212Codecs.KeyLookup keys;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder idleSeconds(int idleSeconds) {
            this.idleSeconds = idleSeconds;
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
         * 校验 MN 和密码，默认全部放行
         */
        public Builder passwordChecker(BiPredicate<String, String> passwordChecker) {
            this.passwordChecker = passwordChecker;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * 现场机请求校时（1013）时，自动下发平台时间（1012），默认开启
         */
        public Builder autoTimeSync(boolean on) {
            this.autoTimeSync = on;
            return this;
        }

        /**
         * 按 MN 查 2025 版的通信密钥（SM4），用来解密加密报文。不设置的话加密报文 CRC 对不上，会被丢弃
         */
        public Builder keys(Hj212Codecs.KeyLookup keys) {
            this.keys = keys;
            return this;
        }

        public Hj212Server build() {
            return new Hj212Server(this);
        }
    }
}
