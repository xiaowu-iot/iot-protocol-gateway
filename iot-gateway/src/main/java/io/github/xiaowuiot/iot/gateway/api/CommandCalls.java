package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.body.GeneralResponse;
import io.github.xiaowuiot.protocol.jt808.server.Jt808CommandSender;
import io.github.xiaowuiot.protocol.jt808.server.Jt808Server;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 下发 808 指令并把应答转成 HTTP 响应：不在线 404、超时 504、发送失败 502。
 */
final class CommandCalls {

    private CommandCalls() {
    }

    static CompletableFuture<ResponseEntity<Object>> call(Jt808Server s, String deviceId, int msgId, Object body,
                                                          int timeout, Function<Jt808Message, Object> mapper) {
        if (s == null) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "808 服务未启用")));
        }
        return s.getCommandSender().send(deviceId, msgId, body, timeout, TimeUnit.SECONDS)
                .thenApply(msg -> ResponseEntity.ok(mapper.apply(msg)))
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    HttpStatus status = cause instanceof Jt808CommandSender.DeviceOfflineException ? HttpStatus.NOT_FOUND
                            : cause instanceof TimeoutException ? HttpStatus.GATEWAY_TIMEOUT
                            : HttpStatus.BAD_GATEWAY;
                    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(cause.getMessage())));
                });
    }

    static Object generalResult(Jt808Message msg) {
        GeneralResponse r = msg.getBody();
        return Map.of("result", r.getResult(), "success", r.isSuccess());
    }
}
