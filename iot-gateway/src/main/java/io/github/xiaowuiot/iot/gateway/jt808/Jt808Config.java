package io.github.xiaowuiot.iot.gateway.jt808;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionListener;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.gateway.config.IotProperties;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.github.xiaowuiot.iot.gateway.sink.MessageSinks;
import io.github.xiaowuiot.iot.jt808.server.InMemoryAuthService;
import io.github.xiaowuiot.iot.jt808.server.Jt808AuthService;
import io.github.xiaowuiot.iot.jt808.server.Jt808Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 装配 808 接入服务。
 * <p>
 * 用 {@link SmartLifecycle} 控制启停：Spring 容器准备好之后再开始接收设备连接，停机时先关闭设备连接，再销毁其他 Bean，
 * 避免设备数据进来了，下游的 Kafka 客户端却已经关掉了。
 */
@Configuration
@ConditionalOnProperty(prefix = "iot.jt808", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Jt808Config {

    @Bean
    @ConditionalOnMissingBean
    public Jt808AuthService jt808AuthService() {
        return new InMemoryAuthService();
    }

    @Bean
    public Jt808Server jt808Server(IotProperties props, SessionManager sessions, Jt808AuthService authService,
                                   MessageSinks sinks) {
        IotProperties.Server cfg = props.getJt808();
        return Jt808Server.builder()
                .port(cfg.getPort())
                .idleSeconds(cfg.getIdleSeconds())
                .workerThreads(cfg.getWorkerThreads())
                .sessions(sessions)
                .authService(authService)
                .listener((session, msg) -> {
                    List<DeviceMessage> messages = Jt808MessageConverter.convert(session.getDeviceId(), msg);
                    if (messages.size() == 1) {
                        return sinks.send(messages.get(0));
                    }
                    return CompletableFuture.allOf(messages.stream().map(sinks::send).toArray(CompletableFuture[]::new));
                })
                .build();
    }

    @Bean
    public SmartLifecycle jt808ServerLifecycle(Jt808Server server, SessionManager sessions, MessageSinks sinks) {
        sessions.addListener(new SessionListener() {
            @Override
            public void onOnline(Session session) {
                sinks.send(DeviceMessage.of(session.getDeviceId(), session.getProtocol(), DeviceMessage.TYPE_ONLINE, null)
                        .put("remote", String.valueOf(session.getChannel().remoteAddress())));
            }

            @Override
            public void onOffline(Session session, boolean replaced) {
                // 被新连接挤掉的旧会话不算离线，设备其实还在线
                if (!replaced) {
                    sinks.send(DeviceMessage.of(session.getDeviceId(), session.getProtocol(), DeviceMessage.TYPE_OFFLINE, null)
                            .put("onlineSeconds", (System.currentTimeMillis() - session.getCreateTime()) / 1000));
                }
            }
        });
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    server.start();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                running = true;
            }

            @Override
            public void stop() {
                server.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
