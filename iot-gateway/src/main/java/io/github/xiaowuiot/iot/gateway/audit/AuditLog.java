package io.github.xiaowuiot.iot.gateway.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 设备连接记录和指令记录。
 * <p>
 * 网关不带数据库：最近的记录放在内存里给接口查询（条数有上限，重启就没了），
 * 同时每条写一行 JSON 到日志 {@code iot.audit}，logback 配置里单独落到 iot-audit.log，长期留存和检索用它。
 */
public class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("iot.audit");

    /**
     * 连接记录
     *
     * @param event  CONNECT 连接、AUTH 注册鉴权登录、ONLINE 上线、OFFLINE 下线、DISCONNECT 断开
     * @param action 中文说明，比如"鉴权成功""心跳超时断开"
     */
    public record ConnectionRecord(long id, Instant time, String protocol, String deviceId, String event, String action,
                                   boolean success, String remote, String detail) {
    }

    /**
     * 通过接口下发的指令
     *
     * @param status  接口返回的 HTTP 状态：200 设备有应答，404 不在线，504 超时，502 发送失败
     * @param request 请求参数（接口的查询参数和请求体）
     * @param result  设备应答或失败原因
     */
    public record CommandRecord(long id, Instant time, String protocol, String deviceId, String command, String method,
                                String path, String request, int status, boolean success, String result, long durationMs,
                                String caller) {
    }

    private final ObjectMapper mapper;
    private final int maxRecords;
    private final AtomicLong ids = new AtomicLong();
    private final Store<ConnectionRecord> connections = new Store<>();
    private final Store<CommandRecord> commands = new Store<>();

    public AuditLog(ObjectMapper mapper, int maxRecords) {
        this.mapper = mapper;
        this.maxRecords = Math.max(100, maxRecords);
    }

    public void connection(String protocol, String deviceId, String event, String action, boolean success,
                           String remote, String detail) {
        ConnectionRecord r = new ConnectionRecord(ids.incrementAndGet(), Instant.now(), protocol, deviceId, event, action,
                success, remote, detail);
        connections.add(r, maxRecords);
        write("connection", r);
    }

    public void command(String protocol, String deviceId, String command, String method, String path, String request,
                        int status, String result, long durationMs, String caller) {
        CommandRecord r = new CommandRecord(ids.incrementAndGet(), Instant.now(), protocol, deviceId, command, method, path,
                request, status, status < 400, result, durationMs, caller);
        commands.add(r, maxRecords);
        write("command", r);
    }

    public List<ConnectionRecord> connections(Predicate<ConnectionRecord> filter, int limit) {
        return connections.query(filter, limit);
    }

    public List<CommandRecord> commands(Predicate<CommandRecord> filter, int limit) {
        return commands.query(filter, limit);
    }

    private void write(String type, Object record) {
        try {
            AUDIT.info("{} {}", type, mapper.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            AUDIT.info("{} {}", type, record);
        }
    }

    /** 新的在前，超过上限丢最旧的 */
    private static final class Store<T> {
        private final ConcurrentLinkedDeque<T> deque = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger();

        void add(T t, int max) {
            deque.addFirst(t);
            if (size.incrementAndGet() > max && deque.pollLast() != null) {
                size.decrementAndGet();
            }
        }

        List<T> query(Predicate<T> filter, int limit) {
            List<T> out = new ArrayList<>();
            for (Iterator<T> it = deque.iterator(); it.hasNext() && out.size() < limit; ) {
                T t = it.next();
                if (filter.test(t)) {
                    out.add(t);
                }
            }
            return out;
        }
    }
}
