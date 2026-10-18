package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.codec.command.PendingRequests;
import io.github.xiaowuiot.iot.codec.session.Session;
import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.GeneralResponse;
import io.github.xiaowuiot.iot.jt808.body.InfoBodies;
import io.github.xiaowuiot.iot.jt808.body.LocationQueryResponse;
import io.github.xiaowuiot.iot.jt808.body.Multimedia;
import io.github.xiaowuiot.iot.jt808.body.Recorder;
import io.github.xiaowuiot.iot.jt808.body.TerminalParams;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 平台下发指令，并等待终端应答。
 * <pre>
 * CompletableFuture&lt;Jt808Message&gt; f = sender.send("13800138000", MessageId.QUERY_PARAMS, null, 5, TimeUnit.SECONDS);
 * </pre>
 * 应答怎么对上号：下发时取一个流水号，终端的应答里会带回"应答流水号"，用"设备号 + 流水号"匹配。
 * 终端对不同指令的应答方式不同：大部分回通用应答 0x0001，查询参数回 0x0104，查询位置回 0x0201，
 * 它们的消息体第一个字段都是应答流水号。
 */
public class Jt808CommandSender {

    public static final String PROTOCOL = "jt808";

    /**
     * 会话里保存终端的消息头模板（版本、手机号），下发时按终端的版本组包
     */
    public static final String HEADER_TEMPLATE = "jt808.header";

    /**
     * 下发指令 → 应答消息 ID，这些应答没有"应答流水号"字段，比如查询终端属性 0x8107 的应答 0x0107。
     * 只能按应答类型匹配，所以同一台终端同时只能有一个这类请求在等应答
     */
    private static final Map<Integer, Integer> NO_SERIAL_RESPONSES = new HashMap<>();

    static {
        NO_SERIAL_RESPONSES.put(MessageId.QUERY_ATTRIBUTES, MessageId.ATTRIBUTES_RESPONSE);
        // 0x0702 既是 0x8702 的应答，也是插拔卡时终端主动上报的消息，本身没有应答流水号
        NO_SERIAL_RESPONSES.put(MessageId.DRIVER_INFO_REQUEST, MessageId.DRIVER_INFO);
    }

    private final SessionManager sessions;
    private final PendingRequests<Jt808Message> pending;

    public Jt808CommandSender(SessionManager sessions) {
        this(sessions, new PendingRequests<>());
    }

    public Jt808CommandSender(SessionManager sessions, PendingRequests<Jt808Message> pending) {
        this.sessions = sessions;
        this.pending = pending;
    }

    /**
     * 下发并等待应答。
     *
     * @param body 消息体，没有消息体的指令（比如查询参数）传 null
     * @return 终端的应答消息；设备不在线、发送失败、超时都会以异常结束
     */
    public CompletableFuture<Jt808Message> send(String deviceId, int msgId, Object body, long timeout, TimeUnit unit) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive()) {
            CompletableFuture<Jt808Message> f = new CompletableFuture<>();
            f.completeExceptionally(new DeviceOfflineException(deviceId));
            return f;
        }
        int serial = session.nextSerialNo();
        Jt808Header header = Jt808Header.to(session.<Jt808Header>getAttribute(HEADER_TEMPLATE), msgId, serial);
        // 应答里没有应答流水号的指令，只能按"等的是哪种应答"登记
        Integer noSerialResponse = NO_SERIAL_RESPONSES.get(msgId);
        int key = noSerialResponse != null ? typeKey(noSerialResponse) : serial;
        // 先登记再发送，否则终端应答得快的话，应答会比登记先到
        CompletableFuture<Jt808Message> future = pending.register(deviceId, key, timeout, unit);
        session.write(new Jt808Message(header, body)).addListener(f -> {
            if (!f.isSuccess()) {
                pending.fail(deviceId, key, f.cause());
            }
        });
        return future;
    }

    /**
     * 只发送，不等应答，比如回复终端的应答。
     */
    public boolean sendOnly(String deviceId, int msgId, Object body) {
        Session session = sessions.get(deviceId);
        if (session == null || !session.isActive()) {
            return false;
        }
        Jt808Header header = Jt808Header.to(session.<Jt808Header>getAttribute(HEADER_TEMPLATE), msgId, session.nextSerialNo());
        session.write(new Jt808Message(header, body));
        return true;
    }

    /**
     * 收到终端的应答类消息时调用。
     *
     * @return false 表示没有在等这条应答（已经超时了，或者是重复的应答）
     */
    public boolean onResponse(Session session, Jt808Message msg) {
        Integer replySerial = replySerialNo(msg.getBody());
        if (replySerial != null) {
            return pending.complete(session.getDeviceId(), replySerial, msg);
        }
        return pending.complete(session.getDeviceId(), typeKey(msg.getMsgId()), msg);
    }

    public int pendingCount() {
        return pending.size();
    }

    private static Integer replySerialNo(Object body) {
        if (body instanceof GeneralResponse) {
            return ((GeneralResponse) body).getReplySerialNo();
        }
        if (body instanceof TerminalParams) {
            return ((TerminalParams) body).getReplySerialNo();
        }
        if (body instanceof LocationQueryResponse) {
            return ((LocationQueryResponse) body).getReplySerialNo();
        }
        if (body instanceof Multimedia.CameraResponse) {
            return ((Multimedia.CameraResponse) body).getReplySerialNo();
        }
        if (body instanceof InfoBodies.QuestionAnswer) {
            return ((InfoBodies.QuestionAnswer) body).getReplySerialNo();
        }
        if (body instanceof InfoBodies.VehicleControlResponse) {
            return ((InfoBodies.VehicleControlResponse) body).getReplySerialNo();
        }
        if (body instanceof Recorder.Upload) {
            return ((Recorder.Upload) body).getReplySerialNo();
        }
        return null;
    }

    /**
     * 按应答类型等待时用的 key：流水号只有 0~65535，加上 0x10000 就不会和真实的流水号冲突
     */
    private static int typeKey(int responseMsgId) {
        return 0x10000 + responseMsgId;
    }

    public static class DeviceOfflineException extends RuntimeException {
        public DeviceOfflineException(String deviceId) {
            super("设备不在线：" + deviceId);
        }
    }
}
