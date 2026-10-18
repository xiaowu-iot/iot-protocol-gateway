package io.github.xiaowuiot.iot.gateway.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.concurrent.CompletableFuture;

/**
 * 写入 Kafka。
 * <ul>
 *     <li>按数据类型分 topic：位置量大、报警量小但要求实时，分开后报警不会被位置数据淹没</li>
 *     <li>key 用设备号：同一台设备的消息进同一个分区，保证顺序</li>
 *     <li>broker 确认写入后，future 才完成，网关这时才给设备回应答</li>
 * </ul>
 * 注意 {@code producer.send()} 并不是完全不阻塞：拉取元数据、发送缓冲区满的时候会阻塞，最长 {@code max.block.ms}。
 * 它是在 Netty 的 IO 线程里调用的，所以 {@code max.block.ms} 要设得很小，见 {@code GatewayConfig}。
 */
public class KafkaSink implements MessageSink {

    private final Producer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topicPrefix;

    public KafkaSink(Producer<String, String> producer, ObjectMapper mapper, String topicPrefix) {
        this.producer = producer;
        this.mapper = mapper;
        this.topicPrefix = topicPrefix;
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public CompletableFuture<Void> send(DeviceMessage message) {
        String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            producer.send(new ProducerRecord<>(topic(message), message.deviceId(), json), (meta, e) -> {
                if (e == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            // 缓冲区满、元数据拉取超时：直接失败，不回应答，让设备重发
            future.completeExceptionally(e);
        }
        return future;
    }

    String topic(DeviceMessage message) {
        return switch (message.type()) {
            case DeviceMessage.TYPE_LOCATION -> topicPrefix + "location";
            case DeviceMessage.TYPE_ALARM -> topicPrefix + "alarm";
            case DeviceMessage.TYPE_ONLINE, DeviceMessage.TYPE_OFFLINE -> topicPrefix + "status";
            default -> topicPrefix + "event";
        };
    }
}
