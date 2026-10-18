package io.github.xiaowuiot.iot.codec.stats;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 解码过程中被丢弃的数据计数，按原因分类，比如 "frame.GARBAGE"、"jt808.checksum"。
 * <p>
 * 协议库不依赖任何监控框架，这里只做计数；网关把它绑定到 Micrometer 上。
 * 平时这些数接近 0，某个原因的计数突然上涨，通常意味着：某个厂家升级了固件、某批设备的协议版本变了、
 * 或者有人在扫端口。
 */
public final class CodecStats {

    private static final Map<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    private CodecStats() {
    }

    public static void increment(String key) {
        COUNTERS.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public static Map<String, LongAdder> all() {
        return Collections.unmodifiableMap(COUNTERS);
    }
}
