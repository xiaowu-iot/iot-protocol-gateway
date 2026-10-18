package io.github.xiaowuiot.iot.gateway.hj212;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.gateway.config.IotProperties;
import io.github.xiaowuiot.iot.gateway.sink.MessageSinks;
import io.github.xiaowuiot.iot.hj212.Hj212Factors;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 212 接入服务。和 808 共用会话管理器和数据输出，下游收到的都是统一消息模型。
 */
@Configuration
@ConditionalOnProperty(prefix = "iot.hj212", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Hj212FactorProperties.class)
public class Hj212Config {

    private static final Logger log = LoggerFactory.getLogger(Hj212Config.class);

    @Bean
    public Hj212Server hj212Server(IotProperties props, Hj212FactorProperties factors, SessionManager sessions, MessageSinks sinks) {
        IotProperties.Server cfg = props.getHj212();
        factors.getCustomFactors().forEach((code, spec) -> {
            String[] s = spec.split("\\|");
            Hj212Factors.register(code, s[0], s.length > 1 ? s[1] : "", s.length > 2 ? Integer.parseInt(s[2].trim()) : 2);
            log.info("登记自定义因子 {}", Hj212Factors.get(code));
        });
        return Hj212Server.builder()
                .port(cfg.getPort())
                .idleSeconds(cfg.getIdleSeconds())
                .workerThreads(cfg.getWorkerThreads())
                .sessions(sessions)
                .listener((session, packet) -> sinks.send(Hj212MessageConverter.convert(packet)))
                .build();
    }

    @Bean
    public SmartLifecycle hj212ServerLifecycle(Hj212Server server) {
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
