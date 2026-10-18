package io.github.xiaowuiot.iot.gateway.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 以 HTTP POST JSON 推送给下游。适合数据量小、对接方不想接 Kafka 的场景。
 * <p>
 * 用 JDK 自带的异步 HttpClient，不阻塞 IO 线程；下游返回非 2xx 算失败，设备收不到应答会重发。
 */
public class WebhookSink implements MessageSink {

    private final URI uri;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WebhookSink(String url, ObjectMapper mapper) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("启用 webhook 时必须配置 iot.sink.webhook-url");
        }
        this.uri = URI.create(url);
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public CompletableFuture<Void> send(DeviceMessage message) {
        String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).thenAccept(resp -> {
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("webhook 返回 " + resp.statusCode());
            }
        });
    }
}
