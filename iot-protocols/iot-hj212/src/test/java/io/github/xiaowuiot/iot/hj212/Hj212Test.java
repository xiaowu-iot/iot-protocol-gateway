package io.github.xiaowuiot.iot.hj212;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.hj212.codec.Hj212Codecs;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hj212Test {

    /**
     * HJ212-2017 标准里的示例：设置实时数据间隔
     */
    static final String SPEC_FRAME = "##0101QN=20160801085857223;ST=32;CN=1062;PW=100000;"
            + "MN=010000A8900016F000169DC0;Flag=5;CP=&&RtdInterval=30&&1C80\r\n";

    static final String REALTIME_DATA = "QN=20261017083000123;ST=32;CN=2011;PW=123456;MN=88888880000001;Flag=5;"
            + "CP=&&DataTime=20261017083000;w01018-Rtd=101.5,w01018-Flag=N;w21003-Rtd=1.25,w21003-Flag=N;"
            + "w01001-Rtd=7.2,w01001-Flag=N&&";

    @Test
    void specExample() {
        String data = SPEC_FRAME.substring(6, SPEC_FRAME.length() - 6);
        // 长度、CRC 都和标准一致
        assertEquals(SPEC_FRAME, Hj212Format.frame(data));
        Hj212Packet p = Hj212Format.parse(data);
        assertEquals("1062", p.getCn());
        assertEquals("010000A8900016F000169DC0", p.getMn());
        assertTrue(p.needAck());
        assertEquals(1, p.version());
        assertEquals("30", p.getParam("RtdInterval"));
    }

    @Test
    void parseFactors() {
        Hj212Packet p = Hj212Format.parse(REALTIME_DATA);
        assertEquals("20261017083000", p.getParam("DataTime"));
        assertEquals(3, p.getFactors().size());
        assertEquals("101.5", p.getFactors().get("w01018").get("Rtd"));
        assertEquals("N", p.getFactors().get("w21003").get("Flag"));
        // 生成的数据段和原文一致
        assertEquals(REALTIME_DATA, Hj212Format.format(p));
    }

    @Test
    void decodeStickyFramesAndDropBadCrc() {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Hj212Codecs.addTo(c.pipeline());
            }
        });
        String good = Hj212Format.frame(REALTIME_DATA);
        String bad = good.substring(0, good.length() - 6) + "0000\r\n";
        // 坏帧夹在两个好帧中间，一次到达
        ch.writeInbound(ascii(good + bad + good));
        assertNotNull(ch.readInbound());
        assertNotNull(ch.readInbound());
        assertNull(ch.readInbound());
    }

    @Test
    void halfFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Hj212Codecs.addTo(c.pipeline());
            }
        });
        String frame = Hj212Format.frame(REALTIME_DATA);
        ch.writeInbound(ascii(frame.substring(0, 50)));
        assertNull(ch.readInbound());
        ch.writeInbound(ascii(frame.substring(50)));
        Hj212Packet p = ch.readInbound();
        assertEquals("88888880000001", p.getMn());
    }

    @Test
    void serverAcksWhenFlagRequires() {
        SessionManager sessions = new SessionManager();
        List<Hj212Packet> received = new ArrayList<>();
        EmbeddedChannel ch = serverChannel(sessions, (mn, pw) -> "123456".equals(pw), (s, p) -> {
            received.add(p);
            return CompletableFuture.completedFuture(null);
        });
        ch.writeInbound(ascii(Hj212Format.frame(REALTIME_DATA)));

        assertEquals(1, received.size());
        assertNotNull(sessions.get("88888880000001"));
        String ack = ((ByteBuf) ch.readOutbound()).toString(StandardCharsets.US_ASCII);
        // 9014 数据应答，QN 原样带回
        assertTrue(ack.startsWith("##"));
        Hj212Packet p = Hj212Format.parse(ack.substring(6, ack.length() - 6));
        assertEquals("9014", p.getCn());
        assertEquals("20261017083000123", p.getQn());
        assertEquals("91", p.getSt());
    }

    @Test
    void noAckWhenFlagSaysNo() {
        EmbeddedChannel ch = serverChannel(new SessionManager(), (mn, pw) -> true,
                (s, p) -> CompletableFuture.completedFuture(null));
        ch.writeInbound(ascii(Hj212Format.frame(REALTIME_DATA.replace("Flag=5", "Flag=4"))));
        assertNull(ch.readOutbound());
    }

    @Test
    void wrongPasswordDropped() {
        SessionManager sessions = new SessionManager();
        EmbeddedChannel ch = serverChannel(sessions, (mn, pw) -> "000000".equals(pw),
                (s, p) -> CompletableFuture.completedFuture(null));
        ch.writeInbound(ascii(Hj212Format.frame(REALTIME_DATA)));
        assertNull(sessions.get("88888880000001"));
        assertNull(ch.readOutbound());
    }

    private static EmbeddedChannel serverChannel(SessionManager sessions, BiPredicate<String, String> checker,
                                                 Hj212Server.Listener listener) {
        Hj212Server.Handler handler = new Hj212Server.Handler(sessions, checker, listener);
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                Hj212Codecs.addTo(ch.pipeline());
                ch.pipeline().addLast(handler);
            }
        });
    }

    private static ByteBuf ascii(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.US_ASCII);
    }
}
