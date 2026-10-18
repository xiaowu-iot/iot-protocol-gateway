package io.github.xiaowuiot.iot.slp;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.slp.body.SlpBodies;
import io.github.xiaowuiot.iot.slp.codec.SlpCodecs;
import io.github.xiaowuiot.iot.slp.server.SlpServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlpCodecTest {

    static final long DEVICE = 202610170001L;

    @Test
    void loginExampleInProtocolDoc() {
        String hex = encodeHex(new SlpMessage(SlpMessage.LOGIN, 1, DEVICE, new SlpBodies.Login("1.0.0", 24)));
        // 协议文档第 6 节的示例
        assertEquals("AA55010100010000002F2C81D8910007" + "05312E302E3018", hex.substring(0, hex.length() - 4));
        assertEquals("C08E", hex.substring(hex.length() - 4));
    }

    @Test
    void roundTrip() {
        EmbeddedChannel ch = codecChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(encodeHex(
                new SlpMessage(SlpMessage.DOOR_STATUS, 7, DEVICE, new SlpBodies.DoorStatus(1792200000L,
                        Arrays.asList(new int[]{1, 0}, new int[]{2, SlpBodies.DoorStatus.OPEN | SlpBodies.DoorStatus.OCCUPIED})))))));
        SlpMessage m = ch.readInbound();
        assertEquals(SlpMessage.DOOR_STATUS, m.getCommand());
        assertEquals(7, m.getSerialNo());
        assertEquals(DEVICE, m.getDeviceId());
        SlpBodies.DoorStatus s = m.getBody();
        assertEquals(1792200000L, s.getTime());
        assertEquals(3, s.getDoors().get(1)[1]);
    }

    @Test
    void resyncAfterFakeHeader() {
        // 数据里碰巧出现 AA 55，后面的"长度"又刚好合理：切帧时就校验 CRC，失败只跳 1 个字节，
        // 藏在后面的真帧能被找回来
        String real = encodeHex(new SlpMessage(SlpMessage.HEARTBEAT, 9, DEVICE, null));
        String fake = "AA55 01 02 0000 0000000000000000 0005";
        EmbeddedChannel ch = codecChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(fake + real)));
        SlpMessage m = ch.readInbound();
        assertNotNull(m);
        assertEquals(9, m.getSerialNo());
        assertNull(ch.readInbound());
    }

    @Test
    void halfFrameAndSticky() {
        String a = encodeHex(new SlpMessage(SlpMessage.HEARTBEAT, 1, DEVICE, null));
        String b = encodeHex(new SlpMessage(SlpMessage.HEARTBEAT, 2, DEVICE, null));
        EmbeddedChannel ch = codecChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex((a + b).substring(0, 30))));
        assertNull(ch.readInbound());
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex((a + b).substring(30))));
        assertEquals(1, ((SlpMessage) ch.readInbound()).getSerialNo());
        assertEquals(2, ((SlpMessage) ch.readInbound()).getSerialNo());
    }

    @Test
    void unknownCommandKeepsRawData() {
        EmbeddedChannel ch = codecChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(encodeHex(new SlpMessage(0x7F, 1, DEVICE, new byte[]{5, 6})))));
        SlpMessage m = ch.readInbound();
        assertArrayEquals(new byte[]{5, 6}, m.<byte[]>getBody());
    }

    @Test
    void serverFlow() throws Exception {
        SessionManager sessions = new SessionManager();
        List<SlpMessage> received = new ArrayList<>();
        SlpServer server = SlpServer.builder().sessions(sessions).heartbeatSeconds(30)
                .listener((s, msg) -> {
                    received.add(msg);
                    return CompletableFuture.completedFuture(null);
                }).build();
        EmbeddedChannel ch = serverChannel(server);

        // 登录
        SlpMessage ack = request(ch, new SlpMessage(SlpMessage.LOGIN, 1, DEVICE, new SlpBodies.Login("1.0.0", 24)));
        assertEquals(SlpMessage.LOGIN | 0x80, ack.getCommand());
        assertEquals(1, ack.getSerialNo());
        assertEquals(30, ack.<SlpBodies.LoginAck>getBody().getHeartbeatSeconds());
        assertNotNull(sessions.get(String.valueOf(DEVICE)));

        // 格口状态
        SlpMessage r = request(ch, new SlpMessage(SlpMessage.DOOR_STATUS, 2, DEVICE,
                new SlpBodies.DoorStatus(1792200000L, Arrays.asList(new int[]{1, 1}))));
        assertTrue(r.<SlpBodies.Result>getBody().isSuccess());
        assertEquals(1, received.size());

        // 开源版不带固件分发
        SlpMessage c = request(ch, new SlpMessage(SlpMessage.OTA_CHUNK_REQUEST, 3, DEVICE,
                new SlpBodies.OtaChunkRequest("2.0.0", 8192, 4096)));
        assertEquals(SlpBodies.OtaChunk.VERSION_NOT_FOUND, c.<SlpBodies.OtaChunk>getBody().getResult());

        // 平台下发开门，设备应答
        CompletableFuture<SlpMessage> f = server.send(String.valueOf(DEVICE), SlpMessage.OPEN_DOOR,
                new SlpBodies.OpenDoor(5, "ORDER-1"), 3, TimeUnit.SECONDS);
        SlpMessage cmd = decode(ch.readOutbound());
        assertEquals(SlpMessage.OPEN_DOOR, cmd.getCommand());
        assertEquals(5, cmd.<SlpBodies.OpenDoor>getBody().getDoor());
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(encodeHex(cmd.response(new SlpBodies.Result(0))))));
        assertTrue(f.get(1, TimeUnit.SECONDS).<SlpBodies.Result>getBody().isSuccess());
    }

    private static SlpMessage request(EmbeddedChannel ch, SlpMessage msg) {
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(encodeHex(msg))));
        return decode(ch.readOutbound());
    }

    private static SlpMessage decode(ByteBuf buf) {
        EmbeddedChannel c = codecChannel();
        c.writeInbound(buf);
        return c.readInbound();
    }

    static String encodeHex(SlpMessage msg) {
        ByteBuf buf = Unpooled.buffer();
        SlpCodecs.Encoder.encode(msg, buf);
        String hex = HexUtils.toHex(buf);
        buf.release();
        return hex;
    }

    private static EmbeddedChannel codecChannel() {
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                SlpCodecs.addTo(ch.pipeline());
            }
        });
    }

    private static EmbeddedChannel serverChannel(SlpServer server) {
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                server.initPipeline(ch.pipeline());
            }
        });
    }
}
