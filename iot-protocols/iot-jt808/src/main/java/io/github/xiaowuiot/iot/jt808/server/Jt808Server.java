package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.codec.server.TcpServer;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.jt808.codec.Jt808Codecs;

/**
 * 808 接入服务。
 * <pre>
 * Jt808Server server = Jt808Server.builder().port(7611).listener(...).build();
 * server.start();
 * </pre>
 * 流水线：读空闲检测 → 切帧 / 转义 → 消息编解码 → 业务处理。线程模型、TCP 参数、启停顺序在 {@link TcpServer} 里，所有协议共用。
 */
public class Jt808Server {

    private final SessionManager sessions;
    private final Jt808CommandSender commandSender;
    private final TcpServer server;

    private Jt808Server(Builder b) {
        this.sessions = b.sessions != null ? b.sessions : new SessionManager();
        this.commandSender = new Jt808CommandSender(sessions);
        Jt808ServerHandler handler = new Jt808ServerHandler(sessions,
                b.authService != null ? b.authService : new InMemoryAuthService(),
                commandSender,
                b.listener != null ? b.listener : Jt808MessageListener.NOOP);
        this.server = new TcpServer("808 接入服务", Jt808CommandSender.PROTOCOL, b.port, b.idleSeconds, b.workerThreads,
                pipeline -> {
                    Jt808Codecs.addTo(pipeline);
                    pipeline.addLast("handler", handler);
                },
                () -> sessions.closeAll(Jt808CommandSender.PROTOCOL));
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

    public int getPort() {
        return server.getPort();
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

    public Jt808CommandSender getCommandSender() {
        return commandSender;
    }

    public static class Builder {
        private int port = 7611;
        private int idleSeconds = 180;
        private int workerThreads = 0;
        private SessionManager sessions;
        private Jt808AuthService authService;
        private Jt808MessageListener listener;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 读空闲超时，建议设为心跳间隔的 3 倍
         */
        public Builder idleSeconds(int idleSeconds) {
            this.idleSeconds = idleSeconds;
            return this;
        }

        /**
         * IO 线程数，0 表示 Netty 默认值（CPU 核数 × 2）
         */
        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        /**
         * 多个协议共用一个会话管理器时传进来，否则各自新建
         */
        public Builder sessions(SessionManager sessions) {
            this.sessions = sessions;
            return this;
        }

        public Builder authService(Jt808AuthService authService) {
            this.authService = authService;
            return this;
        }

        public Builder listener(Jt808MessageListener listener) {
            this.listener = listener;
            return this;
        }

        public Jt808Server build() {
            return new Jt808Server(this);
        }
    }
}
