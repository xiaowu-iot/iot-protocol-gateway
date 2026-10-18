package io.github.xiaowuiot.iot.jt808.server;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.GeneralResponse;
import io.github.xiaowuiot.iot.jt808.body.Location;
import io.github.xiaowuiot.iot.jt808.body.Register;
import io.github.xiaowuiot.iot.jt808.body.RegisterResponse;
import io.github.xiaowuiot.iot.jt808.body.TerminalParams;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台端流程：注册 → 鉴权 → 心跳 → 位置 → 指令下发。终端这一侧用测试代码手工组包。
 */
class Jt808ServerHandlerTest {

    private final SessionManager sessions = new SessionManager();
    private final Jt808CommandSender sender = new Jt808CommandSender(sessions);
    private final List<Jt808Message> received = new ArrayList<>();
    private EmbeddedChannel ch;
    private int terminalSerial;

    @BeforeEach
    void setUp() {
        Jt808ServerHandler handler = new Jt808ServerHandler(sessions, new InMemoryAuthService(), sender,
                (session, msg) -> {
                    received.add(msg);
                    return CompletableFuture.completedFuture(null);
                });
        ch = channel(handler);
    }

    @Test
    void registerAuthHeartbeatLocation() {
        RegisterResponse rr = send(MessageId.REGISTER, new Register().setProvince(44).setCity(300).setMakerId("TEST1")
                .setModel("M1").setTerminalId("T000001").setPlateColor(2).setPlate("粤B12345")).getBody();
        assertEquals(RegisterResponse.SUCCESS, rr.getResult());
        assertEquals(0, rr.getReplySerialNo());
        // 注册成功不等于上线，鉴权之后才有会话
        assertNull(sessions.get("13800138000"));

        GeneralResponse auth = send(MessageId.AUTH, new Auth(rr.getAuthCode())).getBody();
        assertTrue(auth.isSuccess());
        assertEquals(MessageId.AUTH, auth.getReplyMsgId());
        assertNotNull(sessions.get("13800138000"));

        GeneralResponse hb = send(MessageId.HEARTBEAT, null).getBody();
        assertTrue(hb.isSuccess());

        GeneralResponse loc = send(MessageId.LOCATION, new Location().setLatitude(22.5).setLongitude(114.0)
                .setTime(LocalDateTime.of(2026, 10, 20, 8, 0))).getBody();
        assertTrue(loc.isSuccess());
        assertEquals(1, received.size());

        ch.close();
        assertNull(sessions.get("13800138000"));
    }

    @Test
    void rejectBeforeAuth() {
        GeneralResponse r = send(MessageId.LOCATION, new Location().setLatitude(22.5).setLongitude(114.0)).getBody();
        assertEquals(GeneralResponse.FAILURE, r.getResult());
        assertTrue(received.isEmpty());
    }

    @Test
    void wrongAuthCode() {
        GeneralResponse r = send(MessageId.AUTH, new Auth("WRONG")).getBody();
        assertEquals(GeneralResponse.FAILURE, r.getResult());
        assertNull(sessions.get("13800138000"));
    }

    @Test
    void unknownMessageAnsweredUnsupported() {
        login();
        GeneralResponse r = send(0x0F01, new byte[]{1, 2}).getBody();
        assertEquals(GeneralResponse.UNSUPPORTED, r.getResult());
    }

    @Test
    void listenerExceptionDoesNotDisconnect() {
        // 业务代码有 bug，同步抛异常：不能让设备掉线，当作处理失败、不回应答（终端会重发）
        ch = channel(new Jt808ServerHandler(sessions, new InMemoryAuthService(), sender, (session, msg) -> {
            throw new ClassCastException("业务代码的 bug");
        }));
        login();
        Jt808Message reply = send(MessageId.LOCATION, new Location().setLatitude(22.5).setLongitude(114.0)
                .setTime(LocalDateTime.of(2026, 10, 20, 8, 0)));
        assertNull(reply);
        assertTrue(ch.isActive());
        assertNotNull(sessions.get("13800138000"));
    }

    @Test
    void queryParamsAndWaitForResponse() throws Exception {
        login();
        CompletableFuture<Jt808Message> f = sender.send("13800138000", MessageId.QUERY_PARAMS, null, 3, TimeUnit.SECONDS);

        // 终端收到的指令
        Jt808Message cmd = readOutbound();
        assertEquals(MessageId.QUERY_PARAMS, cmd.getMsgId());

        // 终端回 0x0104，应答流水号就是指令的流水号
        send(MessageId.QUERY_PARAMS_RESPONSE, new TerminalParams(cmd.getHeader().getSerialNo(),
                Collections.<Long, Object>singletonMap(TerminalParams.HEARTBEAT_INTERVAL, 60L)));

        TerminalParams p = f.get(1, TimeUnit.SECONDS).getBody();
        assertEquals(60L, p.getParams().get(TerminalParams.HEARTBEAT_INTERVAL));
        assertEquals(0, sender.pendingCount());
        // 应答类消息平台不需要再回应答
        assertNull(ch.readOutbound());
    }

    @Test
    void commandTimeout() {
        login();
        CompletableFuture<Jt808Message> f = sender.send("13800138000", MessageId.QUERY_PARAMS, null, 200, TimeUnit.MILLISECONDS);
        readOutbound();
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertEquals(0, sender.pendingCount());
    }

    @Test
    void commandToOfflineDevice() {
        CompletableFuture<Jt808Message> f = sender.send("13900000000", MessageId.QUERY_PARAMS, null, 1, TimeUnit.SECONDS);
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(Jt808CommandSender.DeviceOfflineException.class, e.getCause());
    }

    private void login() {
        RegisterResponse rr = send(MessageId.REGISTER, new Register().setMakerId("TEST1").setModel("M1")
                .setTerminalId("T000001").setPlate("粤B12345")).getBody();
        send(MessageId.AUTH, new Auth(rr.getAuthCode()));
    }

    /**
     * 模拟终端发一条消息，返回平台的应答（没有应答返回 null）
     */
    private Jt808Message send(int msgId, Object body) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setPhone("013800138000");
        h.setSerialNo(terminalSerial++);
        writeHex(ch, encodeHex(new Jt808Message(h, body)));
        return readOutbound();
    }

    /**
     * 把平台发出的字节再解码成消息，方便断言
     */
    private Jt808Message readOutbound() {
        ByteBuf buf = ch.readOutbound();
        if (buf == null) {
            return null;
        }
        EmbeddedChannel terminal = channel();
        terminal.writeInbound(buf);
        Jt808Message msg = terminal.readInbound();
        terminal.finishAndReleaseAll();
        return msg;
    }
}
