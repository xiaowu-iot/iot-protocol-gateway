package io.github.xiaowuiot.iot.gateway.sink;

import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;

import java.util.concurrent.CompletableFuture;

/**
 * 消息输出：网关解析出来的数据往哪里送。
 * <p>
 * 返回的 future 完成，表示数据已经安全交给下游，这时网关才给设备回应答。
 * 实现里不能阻塞调用线程（它是 Netty 的 IO 线程）。
 */
public interface MessageSink {

    String name();

    CompletableFuture<Void> send(DeviceMessage message);
}
