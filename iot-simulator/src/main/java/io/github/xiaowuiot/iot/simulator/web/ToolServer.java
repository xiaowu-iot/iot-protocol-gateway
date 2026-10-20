package io.github.xiaowuiot.iot.simulator.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 网页工具的后端：JDK 自带的 HttpServer，页面和接口都在这里，报文日志用 SSE 推给页面。
 * 浏览器不能直接开 TCP 连接，所以由这里替页面连平台、收发报文。
 * <pre>
 * GET  /                 页面
 * GET  /api/events       报文日志（SSE），先回放最近的
 * GET  /api/state        设备当前状态和配置
 * POST /api/config       保存配置
 * POST /api/connect      连接 / 断开（/api/disconnect）
 * GET  /api/catalog      上行报文模板列表、系统编码、命令编码
 * GET  /api/template?id= 生成某个模板的 CP
 * POST /api/preview      组帧预览（不发送）
 * POST /api/send         发送
 * POST /api/raw          发送原始报文
 * POST /api/reply        手动回复收到的某条报文
 * POST /api/parse        解析粘贴的报文
 * POST /api/clear        清空日志
 * </pre>
 * 这个服务能让本机连到任意地址，默认只监听 127.0.0.1。
 */
public final class ToolServer {

    private static final Logger log = LoggerFactory.getLogger(ToolServer.class);

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final EventHub hub = new EventHub(json);
    private final ToolDevice device = new ToolDevice(hub);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "web-tool");
        t.setDaemon(true);
        return t;
    });
    private HttpServer server;

    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::page);
        server.createContext("/api/", this::api);
        server.start();
        log.info("HJ212 调试工具已启动：http://{}:{}", "0.0.0.0".equals(bind) ? "127.0.0.1" : bind, port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        device.shutdown();
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void page(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(ex, 404, "text/plain; charset=utf-8", "404".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = ToolServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                send(ex, 500, "text/plain; charset=utf-8", "页面资源缺失".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", readAll(in));
        }
    }

    private void api(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/".length());
        try {
            if ("events".equals(path)) {
                events(ex);
                return;
            }
            Object result = handle(path, ex);
            sendJson(ex, 200, result == null ? ok() : result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            log.warn("处理 {} 出错", path, e);
            sendJson(ex, 500, error(e.toString()));
        }
    }

    private Object handle(String path, HttpExchange ex) throws Exception {
        switch (path) {
            case "state":
                return device.call(device::state);
            case "config": {
                ToolConfig c = json.readValue(body(ex), ToolConfig.class);
                device.call(() -> {
                    device.applyConfig(c);
                    return null;
                });
                return device.call(device::state);
            }
            case "connect":
                device.call(() -> {
                    device.connect();
                    return null;
                });
                return null;
            case "disconnect":
                device.call(() -> {
                    device.disconnect();
                    return null;
                });
                return null;
            case "catalog": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("templates", Hj212Templates.CATALOG);
                m.put("st", Hj212Names.ST);
                m.put("cn", Hj212Names.CN);
                return m;
            }
            case "template": {
                String id = query(ex, "id");
                return device.call(() -> device.template(id));
            }
            case "preview": {
                ToolDevice.SendRequest q = json.readValue(body(ex), ToolDevice.SendRequest.class);
                return device.call(() -> device.preview(q));
            }
            case "send": {
                ToolDevice.SendRequest q = json.readValue(body(ex), ToolDevice.SendRequest.class);
                return device.call(() -> device.send(q));
            }
            case "raw": {
                JsonNode n = json.readTree(body(ex));
                String text = n.path("text").asText("");
                boolean fix = n.path("fix").asBoolean(false);
                return device.call(() -> device.sendRaw(text, fix));
            }
            case "reply": {
                JsonNode n = json.readTree(body(ex));
                long id = n.path("id").asLong();
                String cn = n.path("cn").asText();
                int code = n.path("code").asInt(1);
                return device.call(() -> device.reply(id, cn, code));
            }
            case "parse": {
                JsonNode n = json.readTree(body(ex));
                String text = n.path("text").asText("");
                String key = n.path("key").asText("");
                return device.call(() -> device.parse(text, key));
            }
            case "clear":
                hub.clear();
                return null;
            default:
                throw new IllegalArgumentException("没有接口 " + path);
        }
    }

    /**
     * SSE：一个页面一个队列，15 秒没有消息发一行注释保活
     */
    private void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> q = hub.subscribe();
        try (OutputStream out = ex.getResponseBody()) {
            try {
                out.write(("data: " + json.writeValueAsString(stateMessage()) + "\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 状态取不到不影响日志
            }
            out.flush();
            while (true) {
                String s = q.poll(15, TimeUnit.SECONDS);
                out.write((s == null ? ": ping\n\n" : "data: " + s + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // 页面关了
        } finally {
            hub.unsubscribe(q);
        }
    }

    private Map<String, Object> stateMessage() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "state");
        m.put("state", device.call(device::state));
        return m;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals(name)) {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("缺少参数 " + name);
    }

    private static String body(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            throw new IllegalArgumentException("要用 POST");
        }
        try (InputStream in = ex.getRequestBody()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange ex, int status, Object o) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.writeValueAsBytes(o));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
