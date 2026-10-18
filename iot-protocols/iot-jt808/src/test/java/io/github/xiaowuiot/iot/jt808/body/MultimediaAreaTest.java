package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.codec.Jt808Subpackages;
import io.github.xiaowuiot.iot.jt808.server.InMemoryAuthService;
import io.github.xiaowuiot.iot.jt808.server.Jt808CommandSender;
import io.github.xiaowuiot.iot.jt808.server.Jt808ServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议详解 P12 涉及的多媒体、区域消息。
 */
class MultimediaAreaTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 17, 9, 0, 0);

    @Test
    void cameraCommand() {
        String hex = roundTrip(MessageId.CAMERA_COMMAND, new Multimedia.CameraCommand(1, 1, 0));
        System.out.println("0x8801 拍照：" + hex);
        Multimedia.CameraCommand c = decode(hex).getBody();
        assertEquals(1, c.getChannel());
        assertEquals(1, c.getCommand());
        assertFalse(c.isSave());
    }

    @Test
    void cameraResponse() {
        Multimedia.CameraResponse r = decode(roundTrip(MessageId.CAMERA_RESPONSE,
                new Multimedia.CameraResponse(5, 0, Arrays.asList(1001L)))).getBody();
        assertEquals(5, r.getReplySerialNo());
        assertEquals(Collections.singletonList(1001L), r.getMediaIds());
        // 失败时没有多媒体 ID 列表
        Multimedia.CameraResponse fail = decode(roundTrip(MessageId.CAMERA_RESPONSE,
                new Multimedia.CameraResponse(6, Multimedia.CameraResponse.CHANNEL_UNSUPPORTED, Collections.emptyList()))).getBody();
        assertEquals(2, fail.getResult());
    }

    /**
     * 3000 字节的图片分 4 包上传：前 3 包平台回通用应答，收齐后回 0x8800
     */
    @Test
    void subpackagedImageUpload() {
        List<Jt808Message> received = new ArrayList<>();
        SessionManager sessions = new SessionManager();
        EmbeddedChannel ch = channel(new Jt808ServerHandler(sessions, new InMemoryAuthService(),
                new Jt808CommandSender(sessions), (s, m) -> {
            received.add(m);
            return CompletableFuture.completedFuture(null);
        }));
        login(ch);

        byte[] jpeg = fakeJpeg(3000);
        Location loc = new Location().setStatus(0b11).setLatitude(22.543096).setLongitude(114.057865).setTime(T0);
        Multimedia.Data data = new Multimedia.Data(1001, Multimedia.TYPE_IMAGE, Multimedia.FORMAT_JPEG,
                Multimedia.EVENT_PLATFORM_COMMAND, 1, loc, jpeg);
        AtomicInteger serial = new AtomicInteger(10);
        List<Jt808Message> packets = Jt808Subpackages.split(header(MessageId.MULTIMEDIA_DATA, 0), data,
                Jt808Subpackages.DEFAULT_PACKET_SIZE, serial::getAndIncrement);
        // 消息体 = 8 + 28 + 3000 = 3036 字节，每包 1000：4 包
        assertEquals(4, packets.size());

        List<Integer> replies = new ArrayList<>();
        for (Jt808Message p : packets) {
            writeHex(ch, encodeHex(p));
            replies.add(readOutbound(ch).getMsgId());
        }
        assertEquals(Arrays.asList(MessageId.PLATFORM_GENERAL_RESPONSE, MessageId.PLATFORM_GENERAL_RESPONSE,
                MessageId.PLATFORM_GENERAL_RESPONSE, MessageId.MULTIMEDIA_RESPONSE), replies);

        Multimedia.Data got = received.get(0).getBody();
        assertEquals(1001, got.getMediaId());
        assertArrayEquals(jpeg, got.getData());
        assertEquals(22.543096, got.getLocation().getLatitude(), 1e-9);
        assertEquals(T0, got.getLocation().getTime());
    }

    @Test
    void circleArea() {
        CircleArea.Item limited = new CircleArea.Item(7, CircleArea.ATTR_BY_TIME | CircleArea.ATTR_SPEED_LIMIT
                | CircleArea.ATTR_ENTER_TO_PLATFORM | CircleArea.ATTR_EXIT_TO_PLATFORM,
                22.543096, 114.057865, 500, T0, T0.plusHours(10), 40, 10);
        CircleArea.Item west = CircleArea.Item.simple(8, -33.868820, -70.669265, 200);
        String hex = roundTrip(MessageId.SET_CIRCLE_AREA, new CircleArea(CircleArea.SET_UPDATE, Arrays.asList(limited, west)));
        System.out.println("0x8600 圆形区域：" + hex);
        CircleArea a = decode(hex).getBody();
        assertEquals(2, a.getItems().size());
        assertEquals(40, a.getItems().get(0).maxSpeed);
        assertEquals(T0.plusHours(10), a.getItems().get(0).end);
        assertEquals(-70.669265, a.getItems().get(1).lng, 1e-9);
    }

    @Test
    void geoFence() {
        // 1 度纬度约 111.2 km
        assertEquals(111_195, GeoUtils.distanceMeters(22, 114, 23, 114), 100);
        CircleArea.Item area = CircleArea.Item.simple(1, 22.543096, 114.057865, 200);
        // 向北约 100 米、300 米
        assertTrue(area.contains(22.543096 + 100 / 111_195.0, 114.057865));
        assertFalse(area.contains(22.543096 + 300 / 111_195.0, 114.057865));
    }

    @Test
    void areaAlarmExtra() {
        Location loc = new Location().setAlarm(1L << 20).setStatus(0b11).setLatitude(22.5).setLongitude(114.0)
                .setTime(T0).putExtra(AreaAlarmInfo.ID, new AreaAlarmInfo(AreaAlarmInfo.CIRCLE, 7, AreaAlarmInfo.ENTER));
        Location got = decode(roundTrip(MessageId.LOCATION, loc)).getBody();
        AreaAlarmInfo info = (AreaAlarmInfo) got.getExtras().get(AreaAlarmInfo.ID);
        assertEquals(7, info.getAreaId());
        assertTrue(info.isEnter());
    }

    static byte[] fakeJpeg(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31);
        }
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[size - 2] = (byte) 0xFF;
        b[size - 1] = (byte) 0xD9;
        return b;
    }

    private static void login(EmbeddedChannel ch) {
        RegisterResponse rr = send(ch, MessageId.REGISTER, new Register().setMakerId("TEST1").setModel("M1")
                .setTerminalId("T000001").setPlate("粤B12345"), 1).getBody();
        send(ch, MessageId.AUTH, new Auth(rr.getAuthCode()), 2);
    }

    private static String roundTrip(int msgId, Object body) {
        String hex = encodeHex(new Jt808Message(header(msgId, 1), body));
        assertEquals(hex, encodeHex(decode(hex)));
        return hex;
    }

    private static Jt808Message send(EmbeddedChannel ch, int msgId, Object body, int serial) {
        writeHex(ch, encodeHex(new Jt808Message(header(msgId, serial), body)));
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

    private static Jt808Header header(int msgId, int serial) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setPhone("013800138000");
        h.setSerialNo(serial);
        return h;
    }
}
