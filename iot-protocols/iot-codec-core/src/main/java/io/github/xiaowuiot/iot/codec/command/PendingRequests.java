package io.github.xiaowuiot.iot.codec.command;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 平台下发指令后，等待设备应答。
 * <p>
 * 下发时用"设备号 + 流水号"登记一个 {@link CompletableFuture}，设备的应答里会带回这个流水号，
 * 收到应答时按同样的 key 找到它并完成。超时没收到应答，future 以 {@link TimeoutException} 结束。
 * <p>
 * 超时用的是 {@link HashedWheelTimer}：同时等待的指令可能有几万条，每条都开一个定时任务的话，
 * {@code ScheduledThreadPoolExecutor} 的堆操作开销太大；时间轮精度是 100 毫秒级，对指令超时来说完全够用。
 */
public class PendingRequests<R> {

    private final ConcurrentHashMap<String, Pending<R>> pending = new ConcurrentHashMap<>();
    private final Timer timer;

    public PendingRequests() {
        this(new HashedWheelTimer(r -> {
            Thread t = new Thread(r, "pending-requests-timer");
            t.setDaemon(true);
            return t;
        }, 100, TimeUnit.MILLISECONDS));
    }

    public PendingRequests(Timer timer) {
        this.timer = timer;
    }

    /**
     * 登记一个等待中的请求，必须在<b>真正发送之前</b>调用，否则应答可能比登记先到。
     */
    public CompletableFuture<R> register(String deviceId, int serialNo, long timeout, TimeUnit unit) {
        String key = key(deviceId, serialNo);
        Pending<R> p = new Pending<>();
        Pending<R> old = pending.put(key, p);
        if (old != null) {
            // 流水号回绕了一圈还没等到应答，旧的那条肯定已经超时了
            old.future.completeExceptionally(new IllegalStateException("流水号被复用：" + key));
        }
        p.timeout = timer.newTimeout(t -> {
            if (pending.remove(key, p)) {
                p.future.completeExceptionally(new TimeoutException("等待应答超时：" + key));
            }
        }, timeout, unit);
        return p.future;
    }

    /**
     * 收到设备应答时调用。
     *
     * @return false 表示没有对应的请求：可能已经超时，或者是设备重复发送的应答
     */
    public boolean complete(String deviceId, int serialNo, R response) {
        Pending<R> p = pending.remove(key(deviceId, serialNo));
        if (p == null) {
            return false;
        }
        p.timeout.cancel();
        return p.future.complete(response);
    }

    /**
     * 发送失败时调用，让调用方立即知道结果，而不是傻等到超时。
     */
    public void fail(String deviceId, int serialNo, Throwable cause) {
        Pending<R> p = pending.remove(key(deviceId, serialNo));
        if (p != null) {
            p.timeout.cancel();
            p.future.completeExceptionally(cause);
        }
    }

    public int size() {
        return pending.size();
    }

    private static String key(String deviceId, int serialNo) {
        return deviceId + "#" + serialNo;
    }

    private static final class Pending<R> {
        final CompletableFuture<R> future = new CompletableFuture<>();
        volatile Timeout timeout;
    }
}
