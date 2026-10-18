package io.github.xiaowuiot.iot.gateway.sink;

import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 同时输出到多个 sink，全部成功才算成功。
 * <p>
 * 所有设备数据都经过这里，所以消息计数、每个 sink 的耗时和失败次数都在这里统计：
 * <ul>
 *     <li>{@code iot.messages{protocol, type}}：按协议、类型的消息数</li>
 *     <li>{@code iot.sink.latency{sink}}：从交给 sink 到 sink 确认的耗时，Kafka 变慢第一时间能看到</li>
 *     <li>{@code iot.sink.errors{sink}}：失败次数，失败的消息设备会重发</li>
 * </ul>
 */
public class MessageSinks {

    private final List<MessageSink> sinks;
    private final MeterRegistry registry;
    private final Timer[] latency;
    private final Counter[] errors;

    public MessageSinks(List<MessageSink> sinks) {
        this(sinks, new SimpleMeterRegistry());
    }

    public MessageSinks(List<MessageSink> sinks, MeterRegistry registry) {
        this.sinks = List.copyOf(sinks);
        this.registry = registry;
        this.latency = new Timer[this.sinks.size()];
        this.errors = new Counter[this.sinks.size()];
        for (int i = 0; i < this.sinks.size(); i++) {
            String name = this.sinks.get(i).name();
            latency[i] = Timer.builder("iot.sink.latency").tag("sink", name)
                    .publishPercentiles(0.5, 0.99).register(registry);
            errors[i] = Counter.builder("iot.sink.errors").tag("sink", name).register(registry);
        }
    }

    public CompletableFuture<Void> send(DeviceMessage message) {
        registry.counter("iot.messages", "protocol", message.protocol(), "type", message.type()).increment();
        if (sinks.size() == 1) {
            return metered(0, message);
        }
        CompletableFuture<?>[] futures = new CompletableFuture<?>[sinks.size()];
        for (int i = 0; i < futures.length; i++) {
            futures[i] = metered(i, message);
        }
        return CompletableFuture.allOf(futures);
    }

    private CompletableFuture<Void> metered(int i, DeviceMessage message) {
        long start = System.nanoTime();
        return sinks.get(i).send(message).whenComplete((v, e) -> {
            latency[i].record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            if (e != null) {
                errors[i].increment();
            }
        });
    }

    public List<String> names() {
        return sinks.stream().map(MessageSink::name).toList();
    }
}
