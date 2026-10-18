package io.github.xiaowuiot.iot.codec.util;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流的日志：每个间隔最多打一条，期间被省略的条数在下一条里带上。
 * <p>
 * 为什么需要：下游出问题时，每一条消息都会失败，如果每条都打一行带异常堆栈的日志，
 * 每秒上万条日志本身就能吃满 CPU、写满磁盘，<b>一个下游的故障被日志放大成整个网关的故障</b>。
 * 压测时真实遇到过：下游限流后，网关的 CPU 从 2 核涨到了 30 核，全花在打印异常堆栈上。
 * <p>
 * 用在热路径的失败分支上（每条消息都可能走到的地方）。偶发的错误照常打日志。
 */
public final class RateLimitedLog {

    private final Logger log;
    private final long intervalNanos;
    private final AtomicLong last = new AtomicLong(System.nanoTime() - Long.MAX_VALUE / 2);
    private final AtomicLong suppressed = new AtomicLong();

    public RateLimitedLog(Logger log, long intervalMillis) {
        this.log = log;
        this.intervalNanos = intervalMillis * 1_000_000L;
    }

    /**
     * 打一条 WARN。只有第一条带异常堆栈，之后只打异常的类型和消息
     */
    public void warn(String message, Object detail, Throwable e) {
        long now = System.nanoTime();
        long prev = last.get();
        if (now - prev < intervalNanos || !last.compareAndSet(prev, now)) {
            suppressed.incrementAndGet();
            return;
        }
        long n = suppressed.getAndSet(0);
        if (n == 0) {
            log.warn("{} {}", message, detail, e);
        } else {
            log.warn("{} {}（之前 {} 条同类日志已省略）：{}", message, detail, n, e == null ? "" : e.toString());
        }
    }
}
