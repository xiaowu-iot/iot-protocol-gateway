package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.protocol.slp.SlpMessage;
import io.github.xiaowuiot.protocol.slp.body.SlpBodies;
import io.github.xiaowuiot.protocol.slp.server.SlpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SLP 储物柜：开门。
 * <pre>
 * POST /api/slp/202610170001/open           {"door": 5, "orderNo": "ORDER-1"}
 * </pre>
 */
@RestController
public class SlpController {

    private final ObjectProvider<SlpServer> server;

    public SlpController(ObjectProvider<SlpServer> server) {
        this.server = server;
    }

    @PostMapping("/api/slp/{deviceId}/open")
    public CompletableFuture<ResponseEntity<Object>> open(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        SlpServer s = server.getIfAvailable();
        if (s == null) {
            return CompletableFuture.completedFuture(unavailable());
        }
        SlpBodies.OpenDoor cmd = new SlpBodies.OpenDoor(((Number) body.get("door")).intValue(), (String) body.get("orderNo"));
        return s.send(deviceId, SlpMessage.OPEN_DOOR, cmd, 5, TimeUnit.SECONDS)
                .thenApply(resp -> ResponseEntity.ok((Object) Map.of("result", resp.<SlpBodies.Result>getBody().getCode())))
                .exceptionally(e -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", String.valueOf(e.getMessage()))));
    }

    private static ResponseEntity<Object> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "SLP 服务未启用"));
    }
}
