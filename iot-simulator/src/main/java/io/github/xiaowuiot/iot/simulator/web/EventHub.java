package io.github.xiaowuiot.iot.simulator.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 报文日志：留最近 1000 条，推给所有打开的页面（SSE）。页面刷新后先回放留着的，再接着推新的
 */
final class EventHub {

    private static final int KEEP = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ObjectMapper json;
    private final AtomicLong ids = new AtomicLong();
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private final Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
    private final List<BlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();

    EventHub(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param dir up 上行（设备发出）、down 下行（收到）、sys 工具自己的提示
     */
    long publish(String dir, String level, String title, Map<String, Object> extra) {
        Map<String, Object> e = new LinkedHashMap<>();
        long id = ids.incrementAndGet();
        e.put("type", "event");
        e.put("id", id);
        e.put("time", LocalDateTime.now().format(TIME));
        e.put("dir", dir);
        e.put("level", level);
        e.put("title", title);
        if (extra != null) {
            e.putAll(extra);
        }
        synchronized (this) {
            events.addLast(e);
            byId.put(id, e);
            while (events.size() > KEEP) {
                Map<String, Object> old = events.removeFirst();
                byId.remove((Long) old.get("id"));
            }
        }
        push(e);
        return id;
    }

    long sys(String level, String title) {
        return publish("sys", level, title, null);
    }

    /**
     * 更新某条日志的状态（比如"等待应答"→"已应答 35 ms"）
     */
    void update(long id, String status, String level) {
        Map<String, Object> e;
        synchronized (this) {
            e = byId.get(id);
            if (e == null) {
                return;
            }
        }
        // 序列化时锁的是这条日志本身，修改也锁它
        synchronized (e) {
            e.put("status", status);
            e.put("statusLevel", level);
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("type", "update");
        u.put("id", id);
        u.put("status", status);
        u.put("statusLevel", level);
        push(u);
    }

    synchronized void clear() {
        events.clear();
        byId.clear();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "clear");
        push(c);
    }

    void pushState(Map<String, Object> state) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "state");
        s.put("state", state);
        push(s);
    }

    /**
     * 订阅：返回的队列里先放好回放的历史
     */
    BlockingQueue<String> subscribe() {
        BlockingQueue<String> q = new LinkedBlockingQueue<>(20_000);
        List<Map<String, Object>> backlog;
        synchronized (this) {
            backlog = new ArrayList<>(events);
            subscribers.add(q);
        }
        for (Map<String, Object> e : backlog) {
            q.offer(toJson(e));
        }
        return q;
    }

    /**
     * 当前留着的日志（测试用）
     */
    synchronized List<Map<String, Object>> snapshot() {
        return new ArrayList<>(events);
    }

    void unsubscribe(BlockingQueue<String> q) {
        subscribers.remove(q);
    }

    private void push(Map<String, Object> e) {
        String s = toJson(e);
        for (BlockingQueue<String> q : subscribers) {
            if (!q.offer(s)) {
                // 页面卡住不读了，丢掉这个订阅，页面重连后会回放
                subscribers.remove(q);
            }
        }
    }

    private String toJson(Object o) {
        try {
            synchronized (o) {
                return json.writeValueAsString(o);
            }
        } catch (JsonProcessingException ex) {
            return "{\"type\":\"event\",\"title\":\"序列化失败\"}";
        }
    }
}
