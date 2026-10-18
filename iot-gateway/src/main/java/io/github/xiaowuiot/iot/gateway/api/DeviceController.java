package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 在线设备查询。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final SessionManager sessions;

    public DeviceController(SessionManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public List<DeviceView> list() {
        return sessions.all().stream()
                .sorted(Comparator.comparing(Session::getDeviceId))
                .map(DeviceView::of)
                .toList();
    }

    public record DeviceView(String deviceId, String protocol, String remote, Instant onlineSince, Instant lastActive) {
        static DeviceView of(Session s) {
            return new DeviceView(s.getDeviceId(), s.getProtocol(), String.valueOf(s.getChannel().remoteAddress()),
                    Instant.ofEpochMilli(s.getCreateTime()), Instant.ofEpochMilli(s.getLastActiveTime()));
        }
    }
}
