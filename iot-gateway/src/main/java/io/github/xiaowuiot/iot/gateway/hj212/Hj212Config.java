package io.github.xiaowuiot.iot.gateway.hj212;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.gateway.config.IotProperties;
import io.github.xiaowuiot.iot.gateway.sink.MessageSinks;
import io.github.xiaowuiot.iot.hj212.Hj212Crypto;
import io.github.xiaowuiot.iot.hj212.Hj212Factors;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        Map<String, List<byte[]>> keys = secretKeys(factors.getSecretKeys());
        if (!keys.isEmpty()) {
            log.info("{} 台设备配置了 HJ212-2025 通信密钥", keys.size());
        }
        return Hj212Server.builder()
                .port(cfg.getPort())
                .idleSeconds(cfg.getIdleSeconds())
                .workerThreads(cfg.getWorkerThreads())
                .sessions(sessions)
                .keys(mn -> keys.getOrDefault(mn, Collections.emptyList()))
                .listener((session, packet) -> sinks.send(Hj212MessageConverter.convert(packet)))
                .build();
    }

    /**
     * MN → 密钥列表。MN 不区分大小写，一台设备可以配多个密钥（逗号分隔），格式不对的启动失败
     */
    static Map<String, List<byte[]>> secretKeys(Map<String, String> config) {
        Map<String, List<byte[]>> keys = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        config.forEach((mn, value) -> {
            List<byte[]> list = new ArrayList<>();
            for (String k : value.split(",")) {
                if (!k.isBlank()) {
                    try {
                        list.add(Hj212Crypto.key(k.trim()));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("设备 " + mn + " 的通信密钥格式不对：" + e.getMessage());
                    }
                }
            }
            keys.put(mn, list);
        });
        return keys;
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
