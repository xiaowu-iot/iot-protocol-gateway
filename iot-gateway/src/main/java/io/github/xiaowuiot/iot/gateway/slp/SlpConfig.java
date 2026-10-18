package io.github.xiaowuiot.iot.gateway.slp;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.gateway.config.IotProperties;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.github.xiaowuiot.iot.gateway.sink.MessageSinks;
import io.github.xiaowuiot.iot.slp.SlpMessage;
import io.github.xiaowuiot.iot.slp.body.SlpBodies;
import io.github.xiaowuiot.iot.slp.server.SlpServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 装配 SLP 接入服务。
 */
@Configuration
@ConditionalOnProperty(prefix = "iot.slp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SlpConfig {

    private static final ZoneOffset GMT8 = ZoneOffset.ofHours(8);

    @Bean
    public SlpServer slpServer(IotProperties props, SessionManager sessions, MessageSinks sinks) {
        IotProperties.Server cfg = props.getSlp();
        return SlpServer.builder()
                .port(cfg.getPort())
                // SLP 的心跳间隔由平台在登录应答里下发，空闲超时取它的 3 倍
                .heartbeatSeconds(Math.max(10, cfg.getIdleSeconds() / 3))
                .workerThreads(cfg.getWorkerThreads())
                .sessions(sessions)
                .listener((session, msg) -> {
                    String id = session.getDeviceId();
                    switch (msg.getCommand()) {
                        case SlpMessage.DOOR_STATUS -> {
                            return sinks.send(doorStatus(id, msg.getBody()));
                        }
                        default -> {
                            return sinks.send(DeviceMessage.of(id, SlpServer.PROTOCOL,
                                    String.format("slp_0x%02X", msg.getCommand()), null));
                        }
                    }
                })
                .build();
    }

    static DeviceMessage doorStatus(String id, SlpBodies.DoorStatus s) {
        List<Map<String, Object>> doors = new ArrayList<>();
        for (int[] d : s.getDoors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("door", d[0]);
            m.put("open", (d[1] & SlpBodies.DoorStatus.OPEN) != 0);
            m.put("occupied", (d[1] & SlpBodies.DoorStatus.OCCUPIED) != 0);
            m.put("fault", (d[1] & SlpBodies.DoorStatus.FAULT) != 0);
            doors.add(m);
        }
        // 协议里是 UTC 秒数，转成和其他协议一致的东八区时间
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(s.getTime()), GMT8);
        return DeviceMessage.of(id, SlpServer.PROTOCOL, "door_status", time).put("doors", doors);
    }

    @Bean
    public SmartLifecycle slpServerLifecycle(SlpServer server) {
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
