package io.github.xiaowuiot.iot.gateway.api;

import io.github.xiaowuiot.protocol.codec.util.HexUtils;
import io.github.xiaowuiot.iot.gateway.jt808.Jt808MessageConverter;
import io.github.xiaowuiot.protocol.jt808.Jt808Message;
import io.github.xiaowuiot.protocol.jt808.MessageId;
import io.github.xiaowuiot.protocol.jt808.body.CircleArea;
import io.github.xiaowuiot.protocol.jt808.body.Multimedia;
import io.github.xiaowuiot.protocol.jt808.body.LocationQueryResponse;
import io.github.xiaowuiot.protocol.jt808.body.PassThrough;
import io.github.xiaowuiot.protocol.jt808.body.SimpleBodies;
import io.github.xiaowuiot.protocol.jt808.body.TerminalAttributes;
import io.github.xiaowuiot.protocol.jt808.body.TerminalControl;
import io.github.xiaowuiot.protocol.jt808.body.TerminalParams;
import io.github.xiaowuiot.protocol.jt808.body.TextMessage;
import io.github.xiaowuiot.protocol.jt808.server.Jt808Server;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 808 指令下发。接口是异步的：Tomcat 线程不会傻等终端应答，等待期间线程可以去处理别的请求。
 * <pre>
 * GET  /api/jt808/13800138000/params            查询终端参数
 * PUT  /api/jt808/13800138000/params            设置终端参数，body：{"0x0001": 30}
 * GET  /api/jt808/13800138000/location          查询当前位置
 * POST /api/jt808/13800138000/text              文本下发，body：{"text": "请注意安全"}
 * POST /api/jt808/13800138000/control           终端控制，body：{"command": 4}
 * </pre>
 * 所有接口都可以带 {@code ?timeout=5} 指定等待应答的秒数。
 */
@RestController
@RequestMapping("/api/jt808/{deviceId}")
public class Jt808Controller {

    private final ObjectProvider<Jt808Server> server;

    /**
     * 808 服务可以在配置里关掉，所以这里不直接注入
     */
    public Jt808Controller(ObjectProvider<Jt808Server> server) {
        this.server = server;
    }

    @GetMapping("/params")
    public CompletableFuture<ResponseEntity<Object>> queryParams(@PathVariable String deviceId,
                                                                 @RequestParam(defaultValue = "5") int timeout) {
        return call(deviceId, MessageId.QUERY_PARAMS, null, timeout, msg -> {
            TerminalParams p = msg.getBody();
            Map<String, Object> view = new LinkedHashMap<>();
            p.getParams().forEach((k, v) -> view.put(String.format("0x%04X", k), v));
            return view;
        });
    }

    @PutMapping("/params")
    public CompletableFuture<ResponseEntity<Object>> setParams(@PathVariable String deviceId,
                                                               @RequestBody Map<String, Object> params,
                                                               @RequestParam(defaultValue = "5") int timeout) {
        Map<Long, Object> p = new LinkedHashMap<>();
        params.forEach((k, v) -> p.put(Long.decode(k), v instanceof Number n ? n.longValue() : v));
        return call(deviceId, MessageId.SET_PARAMS, new TerminalParams(p), timeout, Jt808Controller::generalResult);
    }

    @GetMapping("/location")
    public CompletableFuture<ResponseEntity<Object>> location(@PathVariable String deviceId,
                                                              @RequestParam(defaultValue = "5") int timeout) {
        return call(deviceId, MessageId.LOCATION_QUERY, null, timeout,
                msg -> Jt808MessageConverter.location(deviceId, msg.<LocationQueryResponse>getBody().getLocation(), false).data());
    }

    @PostMapping("/text")
    public CompletableFuture<ResponseEntity<Object>> text(@PathVariable String deviceId,
                                                          @RequestBody Map<String, String> body,
                                                          @RequestParam(defaultValue = "5") int timeout) {
        TextMessage text = new TextMessage(TextMessage.FLAG_DISPLAY | TextMessage.FLAG_TTS, body.get("text"));
        return call(deviceId, MessageId.TEXT_MESSAGE, text, timeout, Jt808Controller::generalResult);
    }

    @PostMapping("/control")
    public CompletableFuture<ResponseEntity<Object>> control(@PathVariable String deviceId,
                                                             @RequestBody Map<String, Object> body,
                                                             @RequestParam(defaultValue = "5") int timeout) {
        TerminalControl c = new TerminalControl(((Number) body.get("command")).intValue(), (String) body.get("params"));
        return call(deviceId, MessageId.TERMINAL_CONTROL, c, timeout, Jt808Controller::generalResult);
    }

    /**
     * 下行透传 0x8900：把内容原样交给终端，终端写到对应的串口。
     * <pre>{"type": 65, "text": "T\r\n"} 或者 {"type": 65, "hex": "540D0A"}</pre>
     */
    @PostMapping("/passthrough")
    public CompletableFuture<ResponseEntity<Object>> passThrough(@PathVariable String deviceId,
                                                                 @RequestBody Map<String, Object> body,
                                                                 @RequestParam(defaultValue = "5") int timeout) {
        int type = ((Number) body.get("type")).intValue();
        byte[] content = body.get("hex") != null ? HexUtils.fromHex((String) body.get("hex"))
                : ((String) body.get("text")).getBytes(StandardCharsets.US_ASCII);
        return call(deviceId, MessageId.PASS_THROUGH_DOWN, new PassThrough(type, content), timeout,
                Jt808Controller::generalResult);
    }

    /**
     * 查询指定参数 0x8106：GET /params/select?ids=0x0001,0x0029
     */
    @GetMapping("/params/select")
    public CompletableFuture<ResponseEntity<Object>> queryParamsById(@PathVariable String deviceId,
                                                                     @RequestParam String ids,
                                                                     @RequestParam(defaultValue = "5") int timeout) {
        List<Long> list = new ArrayList<>();
        for (String s : ids.split(",")) {
            list.add(Long.decode(s.trim()));
        }
        return call(deviceId, MessageId.QUERY_PARAMS_BY_ID, new SimpleBodies.QueryParamsById(list), timeout, msg -> {
            Map<String, Object> view = new LinkedHashMap<>();
            msg.<TerminalParams>getBody().getParams().forEach((k, v) -> view.put(String.format("0x%04X", k), v));
            return view;
        });
    }

    /**
     * 查询终端属性 0x8107 → 0x0107
     */
    @GetMapping("/attributes")
    public CompletableFuture<ResponseEntity<Object>> attributes(@PathVariable String deviceId,
                                                                @RequestParam(defaultValue = "5") int timeout) {
        return call(deviceId, MessageId.QUERY_ATTRIBUTES, null, timeout, msg -> msg.<TerminalAttributes>getBody());
    }

    /**
     * 临时位置跟踪 0x8202：{"interval": 5, "valid": 600}，interval 为 0 表示停止
     */
    @PostMapping("/tracking")
    public CompletableFuture<ResponseEntity<Object>> tracking(@PathVariable String deviceId,
                                                              @RequestBody Map<String, Number> body,
                                                              @RequestParam(defaultValue = "5") int timeout) {
        SimpleBodies.TrackingControl t = new SimpleBodies.TrackingControl(body.get("interval").intValue(),
                body.getOrDefault("valid", 0).longValue());
        return call(deviceId, MessageId.TRACKING_CONTROL, t, timeout, Jt808Controller::generalResult);
    }

    /**
     * 人工确认报警 0x8203：{"serialNo": 0, "types": 1}，types 的位和报警标志位对应
     */
    @PostMapping("/alarm-confirm")
    public CompletableFuture<ResponseEntity<Object>> alarmConfirm(@PathVariable String deviceId,
                                                                  @RequestBody Map<String, Number> body,
                                                                  @RequestParam(defaultValue = "5") int timeout) {
        SimpleBodies.AlarmConfirm c = new SimpleBodies.AlarmConfirm(body.getOrDefault("serialNo", 0).intValue(),
                body.get("types").longValue());
        return call(deviceId, MessageId.ALARM_CONFIRM, c, timeout, Jt808Controller::generalResult);
    }

    /**
     * 立即拍照 0x8801：{"channel": 1, "photos": 1}。返回终端分配的多媒体 ID，图片随后通过 0x0801 上传
     */
    @PostMapping("/camera")
    public CompletableFuture<ResponseEntity<Object>> camera(@PathVariable String deviceId,
                                                            @RequestBody Map<String, Number> body,
                                                            @RequestParam(defaultValue = "10") int timeout) {
        Multimedia.CameraCommand cmd = new Multimedia.CameraCommand(body.getOrDefault("channel", 1).intValue(),
                body.getOrDefault("photos", 1).intValue(), body.getOrDefault("interval", 0).intValue());
        return call(deviceId, MessageId.CAMERA_COMMAND, cmd, timeout, msg -> {
            Multimedia.CameraResponse r = msg.getBody();
            return Map.of("result", r.getResult(), "mediaIds", r.getMediaIds());
        });
    }

    /**
     * 设置圆形区域 0x8600：{"setType": 0, "areas": [{"id": 7, "lat": 22.54, "lng": 114.05, "radius": 300}]}。
     * 只设进出区域报警给平台
     */
    @PostMapping("/areas/circle")
    @SuppressWarnings("unchecked")
    public CompletableFuture<ResponseEntity<Object>> circleAreas(@PathVariable String deviceId,
                                                                 @RequestBody Map<String, Object> body,
                                                                 @RequestParam(defaultValue = "5") int timeout) {
        List<CircleArea.Item> items = new ArrayList<>();
        for (Map<String, Number> a : (List<Map<String, Number>>) body.get("areas")) {
            items.add(CircleArea.Item.simple(a.get("id").longValue(), a.get("lat").doubleValue(),
                    a.get("lng").doubleValue(), a.get("radius").longValue()));
        }
        int setType = ((Number) body.getOrDefault("setType", CircleArea.SET_UPDATE)).intValue();
        return call(deviceId, MessageId.SET_CIRCLE_AREA, new CircleArea(setType, items), timeout,
                Jt808Controller::generalResult);
    }

    /**
     * 删除圆形区域 0x8601：DELETE /areas/circle?ids=7,8，不带 ids 表示删除全部
     */
    @DeleteMapping("/areas/circle")
    public CompletableFuture<ResponseEntity<Object>> deleteCircleAreas(@PathVariable String deviceId,
                                                                       @RequestParam(required = false) List<Long> ids,
                                                                       @RequestParam(defaultValue = "5") int timeout) {
        return call(deviceId, MessageId.DELETE_CIRCLE_AREA, new CircleArea.Delete(ids == null ? List.of() : ids),
                timeout, Jt808Controller::generalResult);
    }

    private static Object generalResult(Jt808Message msg) {
        return CommandCalls.generalResult(msg);
    }

    private CompletableFuture<ResponseEntity<Object>> call(String deviceId, int msgId, Object body, int timeout,
                                                           Function<Jt808Message, Object> mapper) {
        return CommandCalls.call(server.getIfAvailable(), deviceId, msgId, body, timeout, mapper);
    }
}
