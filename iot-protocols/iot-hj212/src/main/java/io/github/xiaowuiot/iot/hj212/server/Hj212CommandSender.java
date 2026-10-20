package io.github.xiaowuiot.iot.hj212.server;

import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.hj212.Hj212Commands;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上位机（平台）向现场机下发命令。2017 版的交互流程：
 * <pre>
 * 上位机 ── 请求（QN=新生成，Flag 要求应答）──────▶ 现场机
 * 上位机 ◀── 9011 请求应答（QnRtn，QN 原样带回）── 现场机     1 准备执行；2~9 拒绝及原因
 * 上位机 ◀── 数据（CN 和请求一样，QN 原样带回）── 现场机     有数据返回的命令才有，可能有多条
 * 上位机 ◀── 9012 执行结果（ExeRtn）────────────── 现场机     1 成功；100 没有数据……
 * </pre>
 * 所有回复都用请求的 QN 匹配。超时时间内没收到 9011 就重发（同一个 QN），重发次数用完才算失败：
 * 超时时间和重发次数就是 1000 命令设置给现场机的那两个值，双方要一致。
 */
public class Hj212CommandSender {

    private static final Logger log = LoggerFactory.getLogger(Hj212CommandSender.class);
    private static final DateTimeFormatter QN = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter SYSTEM_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneOffset GMT8 = ZoneOffset.ofHours(8);

    /**
     * 会话里记下设备上报时用的 ST 和 PW，下发命令时照着用
     */
    public static final String ATTR_ST = "hj212.st";
    public static final String ATTR_PW = "hj212.pw";
    public static final String ATTR_VERSION = "hj212.version";

    public static final class Result {
        public final String qn;
        public volatile int qnRtn;
        public volatile Integer exeRtn;
        public final List<Hj212Packet> data = Collections.synchronizedList(new ArrayList<>());
        public volatile int sendTimes;
        /**
         * 通知类命令（比如 2012 停止察看实时数据）：现场机回 9013 通知应答，没有 9011 / 9012
         */
        public volatile boolean notified;

        Result(String qn) {
            this.qn = qn;
        }

        public boolean isSuccess() {
            if (notified) {
                return true;
            }
            return qnRtn == Hj212Commands.QN_READY && exeRtn != null && exeRtn == Hj212Commands.EXE_SUCCESS;
        }

        @Override
        public String toString() {
            if (notified) {
                return "Result{QN=" + qn + ", 通知应答, 发送 " + sendTimes + " 次}";
            }
            return "Result{QN=" + qn + ", QnRtn=" + qnRtn + "(" + Hj212Commands.qnRtnName(qnRtn) + "), ExeRtn="
                    + (exeRtn == null ? "-" : exeRtn + "(" + Hj212Commands.exeRtnName(exeRtn) + ")") + ", 数据 "
                    + data.size() + " 条, 发送 " + sendTimes + " 次}";
        }
    }

    private static final class Pending {
        final Hj212Packet request;
        final Result result;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        volatile boolean ready;
        volatile ScheduledFuture<?> retry;

        Pending(Hj212Packet request) {
            this.request = request;
            this.result = new Result(request.getQn());
        }
    }

    private final SessionManager sessions;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong lastQn = new AtomicLong();
    private volatile int overTimeSeconds = 5;
    private volatile int reCount = 3;

    public Hj212CommandSender(SessionManager sessions) {
        this.sessions = sessions;
    }

    /**
     * 平台等 9011 的超时和重发次数。用 1000 命令设置现场机时，同时调用这里，两边保持一致
     */
    public void setRetry(int overTimeSeconds, int reCount) {
        this.overTimeSeconds = overTimeSeconds;
        this.reCount = reCount;
    }

    /**
     * 下发命令。
     *
     * @param params         CP 里的参数，比如 SystemTime、BeginTime、PolId
     * @param timeoutSeconds 收到 9011 之后，等 9012 的最长时间（历史数据可能要传很久）
     */
    public CompletableFuture<Result> send(String mn, String cn, Map<String, String> params, int timeoutSeconds) {
        Session session = sessions.get(mn);
        if (session == null || !session.isActive()) {
            CompletableFuture<Result> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("设备 " + mn + " 不在线"));
            return f;
        }
        String st = session.getAttribute(ATTR_ST);
        String pw = session.getAttribute(ATTR_PW);
        Integer version = session.getAttribute(ATTR_VERSION);
        // 按现场机的版本组包：给 2005 版的数采仪发 2017 版的版本号，它可能不认
        Hj212Packet p = new Hj212Packet().setQn(nextQn()).setSt(st != null ? st : "32").setCn(cn).setPw(pw).setMn(mn)
                .setFlag((version != null ? version : Hj212Packet.VERSION_2017) << 2 | Hj212Packet.FLAG_NEED_ACK);
        if (params != null) {
            params.forEach(p::putParam);
        }
        Pending pd = new Pending(p);
        String key = key(mn, p.getQn());
        pending.put(key, pd);
        transmit(session, pd, key, timeoutSeconds);
        return pd.future.whenComplete((r, e) -> {
            pending.remove(key);
            if (pd.retry != null) {
                pd.retry.cancel(false);
            }
        });
    }

    private void transmit(Session session, Pending pd, String key, int timeoutSeconds) {
        if (pd.future.isDone() || pd.ready) {
            return;
        }
        if (pd.result.sendTimes > reCount) {
            pd.future.completeExceptionally(new TimeoutException("重发 " + reCount + " 次仍没有收到请求应答 9011"));
            return;
        }
        if (pd.result.sendTimes > 0) {
            log.info("{} 没有收到 {} 的请求应答，第 {} 次重发（QN 不变）", pd.request.getMn(), pd.request.getCn(), pd.result.sendTimes);
            if (Hj212Commands.SET_TIME.equals(pd.request.getCn())) {
                // 校时命令重发时要换成当前时间：原样重发的话，现场机会被设成几秒之前的时间
                pd.request.putParam("SystemTime", LocalDateTime.now(GMT8).format(SYSTEM_TIME));
            }
        }
        pd.result.sendTimes++;
        session.write(pd.request);
        pd.retry = session.getChannel().eventLoop().schedule(() -> transmit(session, pd, key, timeoutSeconds),
                overTimeSeconds, TimeUnit.SECONDS);
        if (pd.result.sendTimes == 1) {
            // 总超时：收到 9011 之后最多再等 timeoutSeconds 秒
            session.getChannel().eventLoop().schedule(() -> pd.future.completeExceptionally(
                    new TimeoutException("没有收到执行结果 9012")), (long) overTimeSeconds * (reCount + 1) + timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 现场机发来的包，如果是某个请求的回复（9011 / 9012 / 9013 或者带同一个 QN 的数据），在这里处理。
     * <p>
     * 2025 版不一样：提取历史数据、提取日志时，返回的每一包数据都有自己的新 QN（附录 C.24~C.26、C.48），
     * 按 QN 对不上。这时按"同一台设备、同一个 CN、已经回过 9011、还没回 9012"的请求来归属。
     *
     * @return true 表示是请求的回复
     */
    public boolean onPacket(Hj212Packet p) {
        Pending pd = pending.get(key(p.getMn(), p.getQn()));
        if (pd == null) {
            pd = waitingForData(p);
        }
        if (pd == null) {
            return false;
        }
        switch (p.getCn()) {
            case Hj212Commands.REQUEST_ACK: {
                int rtn = parseInt(p.getParam("QnRtn"));
                pd.result.qnRtn = rtn;
                pd.ready = true;
                if (pd.retry != null) {
                    pd.retry.cancel(false);
                }
                if (rtn != Hj212Commands.QN_READY) {
                    // 请求被拒绝，后面不会有执行结果
                    pd.future.complete(pd.result);
                }
                break;
            }
            case Hj212Commands.EXEC_RESULT:
                pd.result.exeRtn = parseInt(p.getParam("ExeRtn"));
                if (Hj212Commands.SET_PASSWORD.equals(pd.request.getCn()) && pd.result.isSuccess()) {
                    // 改密码成功：马上换成新密码，否则紧接着下发的命令还带着旧密码，现场机回"PW 错误"
                    Session s = sessions.get(p.getMn());
                    if (s != null) {
                        s.setAttribute(ATTR_PW, pd.request.getParam("NewPW"));
                    }
                }
                pd.future.complete(pd.result);
                break;
            case Hj212Commands.NOTIFY_ACK:
                pd.result.notified = true;
                pd.future.complete(pd.result);
                break;
            default:
                pd.result.data.add(p);
        }
        return true;
    }

    /**
     * 2025 版的命令返回数据：找这台设备正在等数据的同 CN 请求。只认 2025 版的包，2017 版回包的 QN 和请求一样，不需要猜
     */
    private Pending waitingForData(Hj212Packet p) {
        if (p.version() < Hj212Packet.VERSION_2025 || p.getCn() == null || p.getCn().startsWith("9")) {
            return null;
        }
        Pending found = null;
        for (Pending pd : pending.values()) {
            if (pd.ready && !pd.future.isDone() && pd.request.getCn().equals(p.getCn())
                    && pd.request.getMn().equals(p.getMn())
                    && (found == null || pd.request.getQn().compareTo(found.request.getQn()) > 0)) {
                found = pd;
            }
        }
        return found;
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * QN 精确到毫秒。同一毫秒发两条命令的话，QN 会重复，回复就分不清是哪一条的，所以要保证递增
     */
    private String nextQn() {
        long now = LocalDateTime.now(GMT8).toInstant(GMT8).toEpochMilli();
        long qn = lastQn.updateAndGet(prev -> Math.max(prev + 1, now));
        return LocalDateTime.ofEpochSecond(qn / 1000, (int) (qn % 1000) * 1_000_000, GMT8).format(QN);
    }

    private static String key(String mn, String qn) {
        return mn + "/" + qn;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? Hj212Commands.QN_UNKNOWN : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Hj212Commands.QN_UNKNOWN;
        }
    }
}
