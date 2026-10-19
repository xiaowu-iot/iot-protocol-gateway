package io.github.xiaowuiot.iot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.iot.hj212.Hj212Format;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备连接记录和指令记录：212 数采仪连上来、发数据上线、平台下发命令超时、断开，接口都能查到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "iot.jt808.enabled=false",
        "iot.slp.enabled=false",
        "iot.hj212.port=17713"})
class AuditTest {

    private static final String MN = "AUDIT0000000000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void connectionAndCommandRecords() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", 17713)) {
            OutputStream out = socket.getOutputStream();
            String data = "QN=20261020080000000;ST=32;CN=2011;PW=123456;MN=" + MN
                    + ";Flag=4;CP=&&DataTime=20261020080000;w01018-Rtd=28.6,w01018-Flag=N&&";
            out.write(Hj212Format.frame(data).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            List<String> events = waitFor(() -> actions("/api/audit/connections?deviceId=" + MN), List.of("上线"));
            assertThat(events).contains("密码校验成功", "上线");
            assertThat(get("/api/audit/connections?event=CONNECT&protocol=hj212").size()).isPositive();

            // 现场机不应答，平台等 1 秒超时
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/hj212/" + MN + "/time?timeout=1"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(504);
            assertThat(r.body()).contains("error");
            JsonNode cmd = get("/api/audit/commands?deviceId=" + MN).get(0);
            assertThat(cmd.path("command").asText()).isEqualTo("提取现场机时间 1011");
            assertThat(cmd.path("status").asInt()).isEqualTo(504);
            assertThat(cmd.path("success").asBoolean()).isFalse();
            assertThat(cmd.path("request").asText()).isEqualTo("timeout=1");
            assertThat(cmd.path("result").asText()).contains("error");
        }
        List<String> events = waitFor(() -> actions("/api/audit/connections?deviceId=" + MN), List.of("断开连接"));
        assertThat(events).contains("下线", "断开连接");
        assertThat(get("/api/audit/commands?success=true&deviceId=" + MN).size()).isZero();
    }

    private interface Check {
        List<String> get() throws Exception;
    }

    private static List<String> waitFor(Check check, List<String> expected) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        List<String> got = check.get();
        while (!got.containsAll(expected) && System.currentTimeMillis() < end) {
            Thread.sleep(100);
            got = check.get();
        }
        return got;
    }

    private List<String> actions(String path) throws Exception {
        List<String> out = new ArrayList<>();
        get(path).forEach(n -> out.add(n.path("action").asText()));
        return out;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(r.body());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
