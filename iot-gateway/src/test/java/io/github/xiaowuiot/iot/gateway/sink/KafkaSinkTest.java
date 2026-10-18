package io.github.xiaowuiot.iot.gateway.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Kafka 官方的 MockProducer，不需要启动 broker。
 */
class KafkaSinkTest {

    private final MockProducer<String, String> producer =
            new MockProducer<>(false, new StringSerializer(), new StringSerializer());
    private final KafkaSink sink = new KafkaSink(producer, new ObjectMapper().registerModule(new JavaTimeModule()), "iot.");

    @Test
    void completeOnlyAfterBrokerAck() {
        CompletableFuture<Void> f = sink.send(location());
        // broker 还没确认，future 不能完成：这时候网关不能给设备回应答
        assertFalse(f.isDone());

        producer.completeNext();
        assertTrue(f.isDone());
        assertFalse(f.isCompletedExceptionally());

        ProducerRecord<String, String> r = producer.history().get(0);
        assertEquals("iot.location", r.topic());
        assertEquals("13800138000", r.key());
        assertTrue(r.value().contains("\"lat\":22.5"));
    }

    @Test
    void failWhenBrokerRejects() {
        CompletableFuture<Void> f = sink.send(location());
        producer.errorNext(new RuntimeException("broker 不可用"));
        assertTrue(f.isCompletedExceptionally());
    }

    @Test
    void topicByType() {
        assertEquals("iot.alarm", sink.topic(DeviceMessage.of("1", "jt808", DeviceMessage.TYPE_ALARM, null)));
        assertEquals("iot.status", sink.topic(DeviceMessage.of("1", "jt808", DeviceMessage.TYPE_ONLINE, null)));
        assertEquals("iot.status", sink.topic(DeviceMessage.of("1", "jt808", DeviceMessage.TYPE_OFFLINE, null)));
        assertEquals("iot.event", sink.topic(DeviceMessage.of("1", "jt808", "upgrade_result", null)));
    }

    private static DeviceMessage location() {
        return DeviceMessage.of("13800138000", "jt808", DeviceMessage.TYPE_LOCATION, LocalDateTime.of(2026, 10, 17, 8, 0))
                .put("lat", 22.5).put("lng", 114.0);
    }
}
