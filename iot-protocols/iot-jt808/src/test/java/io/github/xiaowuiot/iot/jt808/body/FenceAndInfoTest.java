package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FenceAndInfoTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 18, 8, 0, 0);

    @Test
    void rectangleRoundTrip() {
        RectangleArea.Item it = new RectangleArea.Item(3, Area.ATTR_ENTER_TO_PLATFORM | Area.ATTR_EXIT_TO_PLATFORM | Area.ATTR_SPEED_LIMIT,
                22.55, 114.05, 22.53, 114.07, new Area.Limits(null, null, 30, 10));
        String hex = encodeHex(new Jt808Message(header(MessageId.SET_RECTANGLE_AREA), new RectangleArea(0, Arrays.asList(it))));
        System.out.println("0x8602 矩形区域：" + hex);
        RectangleArea r = decode(hex).getBody();
        RectangleArea.Item back = r.getItems().get(0);
        assertEquals(30, back.maxSpeed());
        assertEquals(10, back.overSpeedSeconds());
        assertTrue(back.contains(22.54, 114.06));
        assertFalse(back.contains(22.56, 114.06));
        assertEquals(hex, encodeHex(decode(hex)));
    }

    @Test
    void polygonContainsConcaveShape() {
        // 一个"凹"字形：中间缺一块
        PolygonArea p = new PolygonArea(5, Area.ATTR_ENTER_TO_PLATFORM, Area.Limits.NONE, Arrays.asList(
                new PolygonArea.Point(22.50, 114.00), new PolygonArea.Point(22.50, 114.03),
                new PolygonArea.Point(22.53, 114.03), new PolygonArea.Point(22.53, 114.02),
                new PolygonArea.Point(22.51, 114.02), new PolygonArea.Point(22.51, 114.01),
                new PolygonArea.Point(22.53, 114.01), new PolygonArea.Point(22.53, 114.00)));
        assertTrue(p.contains(22.505, 114.015));
        assertTrue(p.contains(22.52, 114.005));
        // 缺口里
        assertFalse(p.contains(22.52, 114.015));
        String hex = encodeHex(new Jt808Message(header(MessageId.SET_POLYGON_AREA), p));
        PolygonArea back = decode(hex).getBody();
        assertEquals(8, back.getPoints().size());
        assertEquals(hex, encodeHex(decode(hex)));
    }

    @Test
    void routeSegments() {
        Route route = new Route(9, Route.ATTR_ENTER_TO_PLATFORM | Route.ATTR_EXIT_TO_PLATFORM, null, null, Arrays.asList(
                new Route.Point(1, 101, 22.5400, 114.0500, 40, Route.SEG_DRIVE_TIME | Route.SEG_SPEED_LIMIT, 60, 20, 40, 5),
                new Route.Point(2, 102, 22.5400, 114.0600, 40, Route.SEG_SPEED_LIMIT, 0, 0, 60, 5),
                new Route.Point(3, 103, 22.5500, 114.0600, 40, 0, 0, 0, 0, 0)));
        String hex = encodeHex(new Jt808Message(header(MessageId.SET_ROUTE), route));
        System.out.println("0x8606 路线：" + hex);
        Route back = decode(hex).getBody();
        assertEquals(3, back.getPoints().size());
        assertEquals(60, back.getPoints().get(0).tooLongSeconds);
        assertEquals(60, back.getPoints().get(1).maxSpeed);
        // 第一段中间、偏北 15 米：在路段上
        assertEquals(0, back.segmentAt(22.5400 + 15 / 111000.0, 114.055));
        // 偏北 30 米：超出宽度的一半（20 米）
        assertEquals(-1, back.segmentAt(22.5400 + 30 / 111000.0, 114.055));
        // 第二段（向北）
        assertEquals(1, back.segmentAt(22.545, 114.0601));
        assertEquals(hex, encodeHex(decode(hex)));
    }

    @Test
    void distanceToSegmentMatchesHaversine() {
        double d = GeoUtils.distanceToSegmentMeters(22.5410, 114.0550, 22.5400, 114.0500, 22.5400, 114.0600);
        assertEquals(GeoUtils.distanceMeters(22.5410, 114.0550, 22.5400, 114.0550), d, 0.5);
        // 投影落在线段外，取到端点的距离
        double e = GeoUtils.distanceToSegmentMeters(22.5400, 114.0700, 22.5400, 114.0500, 22.5400, 114.0600);
        assertEquals(GeoUtils.distanceMeters(22.5400, 114.0700, 22.5400, 114.0600), e, 0.5);
    }

    @Test
    void segmentTimeAndOverspeedExtras() {
        Location l = new Location().setStatus(3).setLatitude(22.54).setLongitude(114.05).setSpeed(52).setTime(T0)
                .setAlarm(1L << 1 | 1L << 22)
                .putExtra(AlarmExtras.Overspeed.ID, new AlarmExtras.Overspeed(AlarmExtras.Overspeed.SEGMENT, 101))
                .putExtra(AlarmExtras.SegmentTime.ID, new AlarmExtras.SegmentTime(101, 75, AlarmExtras.SegmentTime.TOO_LONG));
        String hex = encodeHex(new Jt808Message(header(MessageId.LOCATION), l));
        assertTrue(hex.contains("11050400000065"));
        assertTrue(hex.contains("1307000000650"));
        Location back = decode(hex).getBody();
        AlarmExtras.SegmentTime st = (AlarmExtras.SegmentTime) back.getExtras().get(AlarmExtras.SegmentTime.ID);
        assertEquals(75, st.getSeconds());
        assertEquals(AlarmExtras.SegmentTime.TOO_LONG, st.getResult());
        AlarmExtras.Overspeed os = (AlarmExtras.Overspeed) back.getExtras().get(AlarmExtras.Overspeed.ID);
        assertEquals(101, os.getAreaId());
    }

    @Test
    void recorderFrameInsideJt808() {
        byte[] data = Recorder.sampleData(Recorder.CMD_MILEAGE, T0, 12345.6, "粤B12345");
        byte[] packet = Recorder.frame(true, Recorder.CMD_MILEAGE, data);
        System.out.println("GB/T 19056 0x03 累计里程：" + HexUtils.toHex(packet));
        String hex = encodeHex(new Jt808Message(header(MessageId.RECORDER_UPLOAD),
                new Recorder.Upload(7, Recorder.CMD_MILEAGE, packet)));
        System.out.println("0x0700 行驶记录仪数据上传：" + hex);
        Recorder.Upload up = decode(hex).getBody();
        assertEquals(7, up.getReplySerialNo());
        Map<String, Object> m = Recorder.parse(up.cmd, Recorder.unframe(up.block, up.cmd));
        assertEquals(12345.6, m.get("totalKm"));
        assertEquals("2025-03-01T09:00", m.get("installTime"));

        // 改一个字节，记录仪的校验就过不了（808 的校验是重新算的，管不到里面这一层）
        byte[] bad = packet.clone();
        bad[8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Recorder.unframe(bad, Recorder.CMD_MILEAGE));
    }

    @Test
    void recorderVehicleInfo() {
        byte[] packet = Recorder.frame(true, Recorder.CMD_VEHICLE, Recorder.sampleData(Recorder.CMD_VEHICLE, T0, 0, "粤B12345"));
        Map<String, Object> m = Recorder.parse(Recorder.CMD_VEHICLE, Recorder.unframe(packet, Recorder.CMD_VEHICLE));
        assertEquals("LSVAU2180N2123456", m.get("vin"));
        assertEquals("粤B12345", m.get("plate"));
        assertNull(Recorder.parse(0x08, new byte[0]));
    }

    @Test
    void driverInfoConditionalFields() {
        DriverInfo in = new DriverInfo(DriverInfo.CARD_IN, T0, DriverInfo.READ_OK, "张三", "440300000000000001",
                "深圳市交通运输局", LocalDate.of(2030, 6, 30), "440300199001011234");
        String hex2013 = encodeHex(new Jt808Message(header(MessageId.DRIVER_INFO), in));
        System.out.println("0x0702 插卡（2013）：" + hex2013);
        DriverInfo back = decode(hex2013).getBody();
        assertEquals("张三", back.getName());
        assertEquals(LocalDate.of(2030, 6, 30), back.getValidUntil());
        assertNull(back.getIdCard());

        Jt808Header h19 = header(MessageId.DRIVER_INFO);
        h19.setV2019(true);
        h19.setProtocolVersion(1);
        h19.setPhone("00000000013800138000");
        DriverInfo b19 = decode(encodeHex(new Jt808Message(h19, in))).getBody();
        assertEquals("440300199001011234", b19.getIdCard());

        // 拔卡：只有状态和时间，后面什么都没有
        String out = encodeHex(new Jt808Message(header(MessageId.DRIVER_INFO), DriverInfo.cardOut(T0.plusHours(4))));
        DriverInfo o = decode(out).getBody();
        assertEquals(DriverInfo.CARD_OUT, o.getStatus());
        assertNull(o.getName());
        // 读卡失败：有结果，没有姓名
        DriverInfo fail = decode(encodeHex(new Jt808Message(header(MessageId.DRIVER_INFO),
                new DriverInfo(DriverInfo.CARD_IN, T0, 1, null, null, null, null, null)))).getBody();
        assertEquals(1, fail.getReadResult());
        assertNull(fail.getName());
    }

    @Test
    void questionAndEvents() {
        Map<Integer, String> answers = new LinkedHashMap<>();
        answers.put(1, "已到达");
        answers.put(2, "预计 10 分钟");
        answers.put(3, "无法到达");
        String hex = encodeHex(new Jt808Message(header(MessageId.QUESTION),
                new InfoBodies.Question(InfoBodies.Question.TTS, "是否已到达装货点？", answers)));
        System.out.println("0x8302 提问下发：" + hex);
        InfoBodies.Question q = decode(hex).getBody();
        assertEquals("是否已到达装货点？", q.question);
        assertEquals(3, q.answers.size());
        assertEquals("预计 10 分钟", q.answers.get(2));

        Map<Integer, String> events = new LinkedHashMap<>();
        events.put(1, "堵车");
        events.put(2, "车辆故障");
        InfoBodies.EventSetting es = decode(encodeHex(new Jt808Message(header(MessageId.EVENT_SETTING),
                new InfoBodies.EventSetting(InfoBodies.EventSetting.UPDATE, events)))).getBody();
        assertEquals("车辆故障", es.events.get(2));
        String delAll = encodeHex(new Jt808Message(header(MessageId.EVENT_SETTING),
                new InfoBodies.EventSetting(InfoBodies.EventSetting.DELETE_ALL, new LinkedHashMap<>())));
        // 删除全部：消息体只有 1 个字节
        assertEquals(1, decodeBodyLength(delAll));
    }

    @Test
    void vehicleControlResponseCarriesLocation() {
        Location l = new Location().setStatus(3 | 1L << 12).setLatitude(22.54).setLongitude(114.05).setTime(T0);
        String hex = encodeHex(new Jt808Message(header(MessageId.VEHICLE_CONTROL_RESPONSE),
                new InfoBodies.VehicleControlResponse(12, l)));
        InfoBodies.VehicleControlResponse r = decode(hex).getBody();
        assertEquals(12, r.getReplySerialNo());
        assertTrue((r.location.getStatus() & 1L << 12) != 0);
    }

    private static int decodeBodyLength(String hex) {
        return decode(hex).getHeader().getBodyLength();
    }

    private static Jt808Message decode(String hex) {
        EmbeddedChannel c = channel();
        writeHex(c, hex);
        Jt808Message m = readMessages(c).get(0);
        c.finishAndReleaseAll();
        return m;
    }

    private static Jt808Header header(int msgId) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setPhone("013800138000");
        h.setSerialNo(1);
        return h;
    }
}
