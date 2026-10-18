package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.GeneralResponse;
import io.github.xiaowuiot.iot.jt808.body.Location;
import io.github.xiaowuiot.iot.jt808.body.LocationBatch;
import io.github.xiaowuiot.iot.jt808.body.Register;
import io.github.xiaowuiot.iot.jt808.body.TerminalParams;
import io.github.xiaowuiot.iot.jt808.body.UpgradePackage;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.channel;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.encodeHex;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.readMessages;
import static io.github.xiaowuiot.iot.jt808.Jt808TestSupport.writeHex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jt808CodecTest {

    /**
     * 公开文章《手把手解析第一条 JT/T 808 报文》里的位置汇报
     */
    static final String LOCATION_2013 = "7E 02 00 00 25 01 38 00 13 80 00 00 07 00 00 00 00 00 00 00 03 01 57 FA F8"
            + "06 CC 62 89 00 1E 02 58 00 5A 26 10 20 08 30 05 01 04 00 00 30 39 31 01 0C F9 7E";

    static final String REGISTER_2019 = "7E 01 00 40 54 01 00 00 00 00 01 38 00 13 80 00 00 01 00 2C 01 2C 54 45 53 54"
            + "31 00 00 00 00 00 00 4D 31 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
            + "00 00 00 00 00 00 00 00 00 00 00 54 30 30 30 30 30 31 00 00 00 00 00 00 00 00"
            + "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 D4 C1 42 31 32 33 34 35 D4 7E";

    @Test
    void decodeLocation2013() {
        EmbeddedChannel ch = channel();
        writeHex(ch, LOCATION_2013);
        Jt808Message msg = readMessages(ch).get(0);

        Jt808Header h = msg.getHeader();
        assertEquals(MessageId.LOCATION, h.getMsgId());
        assertFalse(h.isV2019());
        assertEquals("013800138000", h.getPhone());
        assertEquals("13800138000", h.deviceId());
        assertEquals(7, h.getSerialNo());

        Location l = msg.getBody();
        assertEquals(22.543096, l.getLatitude(), 1e-9);
        assertEquals(114.057865, l.getLongitude(), 1e-9);
        assertEquals(30, l.getAltitude());
        assertEquals(60.0, l.getSpeed(), 1e-9);
        assertEquals(90, l.getDirection());
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 30, 5), l.getTime());
        assertTrue(l.isAccOn());
        assertTrue(l.isPositioned());
        assertEquals(1234.5, l.getMileageKm(), 1e-9);
        assertEquals(12, l.getSatellites());
    }

    @Test
    void locationRoundTrip() {
        // 编码出来的字节必须和设备发的一模一样
        EmbeddedChannel ch = channel();
        writeHex(ch, LOCATION_2013);
        Jt808Message msg = readMessages(ch).get(0);
        assertEquals(HexUtils.toHex(HexUtils.fromHex(LOCATION_2013)), encodeHex(msg));
    }

    @Test
    void southWestHemisphere() {
        Jt808Header h = header(MessageId.LOCATION, false, 1);
        Location l = new Location().setLatitude(-33.868820).setLongitude(-70.669265).setSpeed(12.3)
                .setTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5)).setStatus(0b11);
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(h, l)));
        Location decoded = readMessages(ch).get(0).getBody();
        assertEquals(-33.868820, decoded.getLatitude(), 1e-9);
        assertEquals(-70.669265, decoded.getLongitude(), 1e-9);
        assertEquals(0b1111, decoded.getStatus());
    }

    @Test
    void decodeRegister2019() {
        EmbeddedChannel ch = channel();
        writeHex(ch, REGISTER_2019);
        Jt808Message msg = readMessages(ch).get(0);
        assertTrue(msg.getHeader().isV2019());
        assertEquals(1, msg.getHeader().getProtocolVersion());
        assertEquals("13800138000", msg.getHeader().deviceId());
        Register r = msg.getBody();
        assertEquals(44, r.getProvince());
        assertEquals(300, r.getCity());
        assertEquals("TEST1", r.getMakerId());
        assertEquals("M1", r.getModel());
        assertEquals("T000001", r.getTerminalId());
        assertEquals("粤B12345", r.getPlate());
        assertEquals(HexUtils.toHex(HexUtils.fromHex(REGISTER_2019)), encodeHex(msg));
    }

    @Test
    void auth2019RoundTrip() {
        Jt808Header h = header(MessageId.AUTH, true, 3);
        String hex = encodeHex(new Jt808Message(h, new Auth("AUTH8888", "860000000000001", "V1.0.0")));
        EmbeddedChannel ch = channel();
        writeHex(ch, hex);
        Auth a = readMessages(ch).get(0).getBody();
        assertEquals("AUTH8888", a.getAuthCode());
        assertEquals("860000000000001", a.getImei());
        assertEquals("V1.0.0", a.getSoftwareVersion());
    }

    @Test
    void responseIsEscaped() {
        // 应答流水号是 126（0x007E），平台发出的应答里也要转义成 7D 02
        Jt808Header h = header(MessageId.PLATFORM_GENERAL_RESPONSE, false, 1);
        String hex = encodeHex(new Jt808Message(h, new GeneralResponse(126, MessageId.HEARTBEAT, 0)));
        assertEquals("7E8001000501380013800000010" + "07D0200020053" + "7E", hex);
    }

    @Test
    void badChecksumDropped() {
        EmbeddedChannel ch = channel();
        writeHex(ch, "7E 00 02 00 00 01 38 00 13 80 00 7D 01 7D 02 AA 7E");
        assertTrue(readMessages(ch).isEmpty());
        // 坏帧不影响后面的好帧
        writeHex(ch, LOCATION_2013);
        assertEquals(1, readMessages(ch).size());
    }

    @Test
    void wrongBodyLengthDropped() {
        // 题库第 1 题出错的版本：终端型号多了一个 00，消息体实际 46 字节，属性里是 45；BCC 和 00 异或不变，校验发现不了
        EmbeddedChannel ch = channel();
        writeHex(ch, "7E 01 00 00 2D 01 38 00 13 80 00 00 01 00 2C 01 2C 54 45 53 54 31 4D 31 00 00"
                + "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 54 30 30 30 30 30 31 02 D4"
                + "C1 42 31 32 33 34 35 EC 7E");
        assertTrue(readMessages(ch).isEmpty());
    }

    @Test
    void unknownMessageKeepsRawBody() {
        Jt808Header h = header(0x0F01, false, 1);
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(h, new byte[]{1, 2, 3})));
        Jt808Message msg = readMessages(ch).get(0);
        assertArrayEquals(new byte[]{1, 2, 3}, msg.<byte[]>getBody());
    }

    @Test
    void terminalParamsRoundTrip() {
        Map<Long, Object> params = new LinkedHashMap<>();
        params.put(TerminalParams.HEARTBEAT_INTERVAL, 60L);
        params.put(TerminalParams.MAIN_SERVER_ADDRESS, "iot.example.com");
        params.put(0x0081L, 44L);
        params.put(TerminalParams.PLATE_COLOR, 2L);
        params.put(TerminalParams.PLATE, "粤B12345");
        Jt808Header h = header(MessageId.QUERY_PARAMS_RESPONSE, false, 5);
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(h, new TerminalParams(9, params))));
        TerminalParams p = readMessages(ch).get(0).getBody();
        assertEquals(9, p.getReplySerialNo());
        assertEquals(params, p.getParams());
    }

    @Test
    void locationBatch() {
        Jt808Header h = header(MessageId.LOCATION_BATCH, false, 8);
        Location a = new Location().setLatitude(22.5).setLongitude(114.0).setTime(LocalDateTime.of(2026, 10, 1, 8, 0));
        Location b = new Location().setLatitude(22.6).setLongitude(114.1).setTime(LocalDateTime.of(2026, 10, 1, 8, 1))
                .putExtra(Location.EXTRA_MILEAGE, 100L);
        EmbeddedChannel ch = channel();
        writeHex(ch, encodeHex(new Jt808Message(h, new LocationBatch(LocationBatch.TYPE_BLIND_AREA, Arrays.asList(a, b)))));
        LocationBatch batch = readMessages(ch).get(0).getBody();
        assertTrue(batch.isBlindArea());
        assertEquals(2, batch.getLocations().size());
        assertEquals(10.0, batch.getLocations().get(1).getMileageKm(), 1e-9);
    }

    @Test
    void subpackageSplitAndAssemble() {
        byte[] firmware = new byte[2500];
        for (int i = 0; i < firmware.length; i++) {
            firmware[i] = (byte) i;
        }
        // 固件里一定有 7E、7D，顺便验证转义
        Jt808Header template = header(MessageId.UPGRADE_PACKAGE, false, 0);
        AtomicInteger serial = new AtomicInteger(100);
        List<Jt808Message> packets = Jt808Subpackages.split(template,
                new UpgradePackage(UpgradePackage.TYPE_TERMINAL, "TEST1", "V2.0.1", firmware), 1000, serial::getAndIncrement);
        assertEquals(3, packets.size());
        assertEquals(101, packets.get(1).getHeader().getSerialNo());

        EmbeddedChannel ch = channel();
        // 故意乱序、重复到达
        writeHex(ch, encodeHex(packets.get(2)));
        writeHex(ch, encodeHex(packets.get(0)));
        writeHex(ch, encodeHex(packets.get(0)));
        writeHex(ch, encodeHex(packets.get(1)));
        List<Jt808Message> msgs = readMessages(ch);
        assertEquals(4, msgs.size());
        assertTrue(msgs.get(0).isFragment());
        assertTrue(msgs.get(2).isFragment());
        Jt808Message full = msgs.get(3);
        assertFalse(full.isFragment());
        UpgradePackage up = full.getBody();
        assertEquals("V2.0.1", up.getVersion());
        assertArrayEquals(firmware, up.getData());
        assertNull(ch.readInbound());
        assertFalse(ch.finishAndReleaseAll());
    }

    static Jt808Header header(int msgId, boolean v2019, int serial) {
        Jt808Header h = new Jt808Header();
        h.setMsgId(msgId);
        h.setV2019(v2019);
        h.setProtocolVersion(v2019 ? 1 : 0);
        h.setPhone(v2019 ? "00000000013800138000" : "013800138000");
        h.setSerialNo(serial);
        return h;
    }
}
