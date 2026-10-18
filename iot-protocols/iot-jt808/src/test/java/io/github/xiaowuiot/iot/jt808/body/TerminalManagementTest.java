package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Flags;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.server.InMemoryAuthService;
import io.github.xiaowuiot.iot.jt808.server.Jt808CommandSender;
import io.github.xiaowuiot.iot.jt808.server.Jt808ServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议详解 P11 涉及的终端管理、位置报警类消息。
 */
class TerminalManagementTest {

    @Test
    void alarmAndStatusNames() {
        // 题库第 3 题：报警 0x00000002，状态 0x00000003
        assertEquals(Arrays.asList("超速报警"), Jt808Flags.alarmNames(0x00000002));
        assertEquals(Arrays.asList("紧急报警", "进出区域", "车辆非法位移"), Jt808Flags.alarmNames(1L | 1L << 20 | 1L << 28));
        assertEquals(Arrays.asList("ACC 开", "已定位"), Jt808Flags.statusNames(0b11));
        // bit8~9 = 11 满载，bit13 前门开，bit18、19 GPS + 北斗
        long status = 0b11 | 0b11L << 8 | 1L << 13 | 1L << 18 | 1L << 19;
        assertEquals(Arrays.asList("ACC 开", "已定位", "满载", "前门开", "定位系统 GPS+北斗"), Jt808Flags.statusNames(status));
        assertTrue((Jt808Flags.CONFIRM_REQUIRED & 1L << 20) != 0);
        assertTrue((Jt808Flags.CONFIRM_REQUIRED & 1L << 1) == 0);
    }

    @Test
    void queryParamsById() {
        String hex = roundTrip(MessageId.QUERY_PARAMS_BY_ID, false,
                new SimpleBodies.QueryParamsById(Arrays.asList(0x0001L, 0x0029L)));
        System.out.println("0x8106 查询指定参数：" + hex);
        SimpleBodies.QueryParamsById q = decode(hex).getBody();
        assertEquals(Arrays.asList(0x0001L, 0x0029L), q.getIds());
    }

    @Test
    void terminalAttributes2013And2019() {
        for (boolean v19 : new boolean[]{false, true}) {
            String hex = roundTrip(MessageId.ATTRIBUTES_RESPONSE, v19, attributes());
            if (!v19) {
                System.out.println("0x0107 终端属性：" + hex);
            }
            TerminalAttributes a = decode(hex).getBody();
            assertEquals("SIM-808", a.getModel());
            assertEquals("89860012345678901234", a.getIccid());
            assertEquals("V2.3.1", a.getFirmwareVersion());
            assertEquals(0b11, a.getGnssAttr());
        }
    }

    @Test
    void trackingControl() {
        String hex = roundTrip(MessageId.TRACKING_CONTROL, false, new SimpleBodies.TrackingControl(5, 600));
        System.out.println("0x8202 临时跟踪：" + hex);
        SimpleBodies.TrackingControl t = decode(hex).getBody();
        assertEquals(5, t.getIntervalSeconds());
        assertEquals(600, t.getValidSeconds());
        // 停止跟踪：只有 2 字节
        SimpleBodies.TrackingControl stop = decode(roundTrip(MessageId.TRACKING_CONTROL, false,
                new SimpleBodies.TrackingControl(0, 0))).getBody();
        assertEquals(0, stop.getIntervalSeconds());
        assertEquals(2, decode(roundTrip(MessageId.TRACKING_CONTROL, false,
                new SimpleBodies.TrackingControl(0, 0))).getHeader().getBodyLength());
    }

    @Test
    void alarmConfirm() {
        String hex = roundTrip(MessageId.ALARM_CONFIRM, false, new SimpleBodies.AlarmConfirm(0, 1L | 1L << 20));
        SimpleBodies.AlarmConfirm c = decode(hex).getBody();
        assertEquals(0, c.getAlarmSerialNo());
        assertEquals(0x00100001L, c.getTypes());
    }

    /**
     * 0x0107 没有应答流水号：按应答类型匹配
     */
    @Test
    void attributesMatchedByResponseType() throws Exception {
        SessionManager sessions = new SessionManager();
        Jt808CommandSender sender = new Jt808CommandSender(sessions);
        EmbeddedChannel ch = channel(new Jt808ServerHandler(sessions, new InMemoryAuthService(), sender,
                (s, m) -> CompletableFuture.completedFuture(null)));
        RegisterResponse rr = send(ch, MessageId.REGISTER, new Register().setMakerId("TEST1").setModel("M1")
                .setTerminalId("T000001").setPlate("粤B12345"), 1).getBody();
        send(ch, MessageId.AUTH, new Auth(rr.getAuthCode()), 2);

        CompletableFuture<Jt808Message> f = sender.send("13800138000", MessageId.QUERY_ATTRIBUTES, null, 3, TimeUnit.SECONDS);
        assertEquals(MessageId.QUERY_ATTRIBUTES, readOutbound(ch).getMsgId());
        send(ch, MessageId.ATTRIBUTES_RESPONSE, attributes(), 3);
        TerminalAttributes a = f.get(1, TimeUnit.SECONDS).getBody();
        assertEquals("SIM-808", a.getModel());
        assertEquals(0, sender.pendingCount());
    }

    private static TerminalAttributes attributes() {
        return new TerminalAttributes().setTerminalType(0b100).setMakerId("SIMU1").setModel("SIM-808")
                .setTerminalId("T138000").setIccid("89860012345678901234").setHardwareVersion("H1.0")
                .setFirmwareVersion("V2.3.1").setGnssAttr(0b11).setCommAttr(1 << 5);
    }

    private static String roundTrip(int msgId, boolean v19, Object body) {
        String hex = encodeHex(new Jt808Message(header(msgId, v19, 1), body));
        assertEquals(hex, encodeHex(decode(hex)));
        return hex;
    }

    private static Jt808Message send(EmbeddedChannel ch, int msgId, Object body, int serial) {
        writeHex(ch, encodeHex(new Jt808Message(header(msgId, false, serial), body)));
        return readOutbound(ch);
    }

    private static Jt808Message readOutbound(EmbeddedChannel ch) {
        ByteBuf buf = ch.readOutbound();
        return buf == null ? null : decode(HexUtils.toHex(buf));
    }

    private static Jt808Message decode(String hex) {
        EmbeddedChannel c = channel();
        writeHex(c, hex);
        Jt808Message m = readMessages(c).get(0);
        c.finishAndReleaseAll();
        return m;
    }

    private static Jt808Header header(int msgId, boolean v19, int serial) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setV2019(v19);
        h.setProtocolVersion(v19 ? 1 : 0);
        h.setPhone(v19 ? "00000000013800138000" : "013800138000");
        h.setSerialNo(serial);
        return h;
    }
}
