package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.iot.gateway.audit.AuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 设备连接记录和指令记录查询，新的在前。
 * <pre>
 * GET /api/audit/connections?deviceId=13800138000&amp;event=AUTH&amp;success=false&amp;limit=50
 *     event：CONNECT 建立连接、AUTH 注册鉴权登录、ONLINE 上线、OFFLINE 下线、DISCONNECT 断开
 * GET /api/audit/commands?deviceId=13800138000&amp;protocol=jt808&amp;success=false
 * </pre>
 * 都可以再加 protocol（jt808 / hj212 / slp）、since（ISO 时间，如 2026-10-20T08:00:00Z）、limit（默认 100，最多 1000）。
 * 内存里只留最近的记录，更早的到 logs/iot-audit.log 里查。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping("/connections")
    public List<AuditLog.ConnectionRecord> connections(@RequestParam(required = false) String deviceId,
                                                       @RequestParam(required = false) String protocol,
                                                       @RequestParam(required = false) String event,
                                                       @RequestParam(required = false) Boolean success,
                                                       @RequestParam(required = false) Instant since,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return audit.connections(r -> (deviceId == null || deviceId.equals(r.deviceId()))
                && (protocol == null || protocol.equals(r.protocol()))
                && (event == null || event.equalsIgnoreCase(r.event()))
                && (success == null || success == r.success())
                && (since == null || !r.time().isBefore(since)), limit(limit));
    }

    @GetMapping("/commands")
    public List<AuditLog.CommandRecord> commands(@RequestParam(required = false) String deviceId,
                                                 @RequestParam(required = false) String protocol,
                                                 @RequestParam(required = false) Boolean success,
                                                 @RequestParam(required = false) Instant since,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return audit.commands(r -> (deviceId == null || deviceId.equals(r.deviceId()))
                && (protocol == null || protocol.equals(r.protocol()))
                && (success == null || success == r.success())
                && (since == null || !r.time().isBefore(since)), limit(limit));
    }

    private static int limit(int limit) {
        return Math.min(Math.max(limit, 1), 1000);
    }
}
