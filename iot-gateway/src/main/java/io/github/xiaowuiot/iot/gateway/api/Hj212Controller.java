package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.iot.hj212.Hj212Commands;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.github.xiaowuiot.iot.hj212.server.Hj212CommandSender;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 212 命令下发。
 * <pre>
 * POST /api/hj212/{mn}/command/{cn}      通用：body 是 CP 里的参数，比如 {"PolId":"w01018","InfoId":"i13001"}
 * GET  /api/hj212/{mn}/time              提取现场机时间（1011），返回和平台时间的偏差
 * POST /api/hj212/{mn}/time              用平台时间校时（1012）
 * GET  /api/hj212/{mn}/history/{cn}      提取历史数据：cn 是 2051 / 2061 / 2031，?begin=yyyyMMddHHmmss&end=...
 * </pre>
 * 返回 9011 / 9012 的结果和命令返回的数据。超时、设备不在线分别返回 504、404。
 */
@RestController
@RequestMapping("/api/hj212/{mn}")
public class Hj212Controller {

    private static final DateTimeFormatter SYSTEM_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectProvider<Hj212Server> server;

    public Hj212Controller(ObjectProvider<Hj212Server> server) {
        this.server = server;
    }

    @PostMapping("/command/{cn}")
    public CompletableFuture<ResponseEntity<Object>> command(@PathVariable String mn, @PathVariable String cn,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             @RequestParam(defaultValue = "30") int timeout) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((k, v) -> params.put(k, String.valueOf(v)));
        }
        return call(mn, cn, params, timeout, r -> {
            if (Hj212Commands.SET_TIMEOUT.equals(cn) && r.isSuccess()) {
                // 平台这边等 9011 的超时、重发次数要和刚设置给现场机的一致
                Hj212Server s = server.getIfAvailable();
                s.getCommandSender().setRetry(Integer.parseInt(params.getOrDefault("OverTime", "5")),
                        Integer.parseInt(params.getOrDefault("ReCount", "3")));
            }
            return result(r);
        });
    }

    @GetMapping("/time")
    public CompletableFuture<ResponseEntity<Object>> getTime(@PathVariable String mn, @RequestParam(defaultValue = "10") int timeout) {
        return call(mn, Hj212Commands.GET_TIME, null, timeout, r -> {
            Map<String, Object> m = result(r);
            if (!r.data.isEmpty() && r.data.get(0).getParam("SystemTime") != null) {
                LocalDateTime device = LocalDateTime.parse(r.data.get(0).getParam("SystemTime"), SYSTEM_TIME);
                // 现场机时间减去平台时间：正数表示现场机快了
                m.put("offsetSeconds", Duration.between(LocalDateTime.now(ZoneOffset.ofHours(8)), device).getSeconds());
            }
            return m;
        });
    }

    @PostMapping("/time")
    public CompletableFuture<ResponseEntity<Object>> setTime(@PathVariable String mn, @RequestParam(defaultValue = "10") int timeout) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("SystemTime", LocalDateTime.now(ZoneOffset.ofHours(8)).format(SYSTEM_TIME));
        return call(mn, Hj212Commands.SET_TIME, params, timeout, Hj212Controller::result);
    }

    @GetMapping("/history/{cn}")
    public CompletableFuture<ResponseEntity<Object>> history(@PathVariable String mn, @PathVariable String cn,
                                                             @RequestParam String begin, @RequestParam String end,
                                                             @RequestParam(defaultValue = "60") int timeout) {
        if (!Hj212Commands.MINUTE.equals(cn) && !Hj212Commands.HOUR.equals(cn) && !Hj212Commands.DAY.equals(cn)) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(Map.of("error", "只支持 2051 / 2061 / 2031")));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("BeginTime", begin);
        params.put("EndTime", end);
        return call(mn, cn, params, timeout, Hj212Controller::result);
    }

    private static Map<String, Object> result(Hj212CommandSender.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qn", r.qn);
        if (r.notified) {
            m.put("notifyAck", true);
        } else {
            m.put("qnRtn", r.qnRtn + " " + Hj212Commands.qnRtnName(r.qnRtn));
            m.put("exeRtn", r.exeRtn == null ? null : r.exeRtn + " " + Hj212Commands.exeRtnName(r.exeRtn));
        }
        m.put("sendTimes", r.sendTimes);
        List<Object> data = new ArrayList<>();
        synchronized (r.data) {
            for (Hj212Packet p : r.data) {
                Map<String, Object> d = new LinkedHashMap<>(p.getParams());
                p.getFactors().forEach(d::put);
                data.add(d);
            }
        }
        if (!data.isEmpty()) {
            m.put("data", data);
        }
        return m;
    }

    private CompletableFuture<ResponseEntity<Object>> call(String mn, String cn, Map<String, String> params, int timeout,
                                                           Function<Hj212CommandSender.Result, Object> mapper) {
        Hj212Server s = server.getIfAvailable();
        if (s == null) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "212 服务未启用")));
        }
        return s.getCommandSender().send(mn, cn, params, timeout)
                .thenApply(r -> ResponseEntity.ok(mapper.apply(r)))
                .exceptionally(e -> {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    HttpStatus status = cause instanceof TimeoutException ? HttpStatus.GATEWAY_TIMEOUT
                            : cause instanceof IllegalStateException ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
                    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(cause.getMessage())));
                });
    }
}
