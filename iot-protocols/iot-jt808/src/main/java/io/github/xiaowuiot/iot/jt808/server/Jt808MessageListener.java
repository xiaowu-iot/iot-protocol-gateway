package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.jt808.Jt808Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 业务数据（位置、批量位置、升级结果、未知消息……）交给谁处理。
 * <p>
 * 返回值决定什么时候给终端回应答：<b>future 成功完成后才回应答</b>。
 * 比如写 Kafka，就在 Kafka 的发送回调里完成 future；写入失败就让 future 失败，不回应答，终端会重发，数据不会丢。
 * <p>
 * 在 IO 线程里调用，不能阻塞。
 */
@FunctionalInterface
public interface Jt808MessageListener {

    Jt808MessageListener NOOP = (session, msg) -> CompletableFuture.completedFuture(null);

    CompletionStage<Void> onMessage(Session session, Jt808Message message);
}
