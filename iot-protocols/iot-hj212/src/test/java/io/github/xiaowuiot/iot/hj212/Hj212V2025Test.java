package io.github.xiaowuiot.iot.hj212;

import io.github.xiaowuiot.iot.codec.session.SessionManager;
import io.github.xiaowuiot.iot.hj212.codec.Hj212Codecs;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HJ212-2025 的报文，用标准原文附录 A、附录 C 的示例做对照
 */
class Hj212V2025Test {

    /**
     * 附录 A.1 的 CRC 示例
     */
    static final String A1 = "##0087QN=20240601085857223;ST=32;CN=1011;PW=123456;MN=010000A8900016F000169DC0;Flag=9;CP=&&&&2200\r\n";

    /**
     * 附录 A.2 示例 1、示例 3 的明文报文
     */
    static final String A2_1 = "QN=20240520210700000;ST=31;CN=2051;PW=123456;MN=010000A8900016F000169DC0;Flag=9;CP=&&DataTime=20240520210600;"
            + "a01011-Min=1.35,a01011-Avg=1.35,a01011-Max=1.35,a01011-Flag=N;a01012-Min=9.3,a01012-Avg=9.3,a01012-Max=9.3,a01012-Flag=N;"
            + "a01014-Min=3.20,a01014-Avg=3.211,a01014-Max=3.212,a01014-Flag=N&&";
    static final String A2_3 = "QN=20240520210700000;ST=31;CN=2051;PW=123456;MN=010000A8900016F000169DC0;Flag=9;CP=&&DataTime=20240520210600;"
            + "a01011-Min=1.35,a01011-Avg=1.35,a01011-Max=1.35,a01011-Flag=N;a01014-Min=3.20,a01014-Avg=3.20,a01014-Max=3.20,a01014-Flag=N&&";

    static final String MN = "010000A8900016F000169DC0";

    /**
     * 附录 A.2 的示例密钥：16 个 0x30
     */
    static final byte[] KEY = Hj212Crypto.key("0000000000000000");

    @Test
    void sm4MatchesGbExample() {
        // GB/T 32907 附录 A 示例 1
        byte[] key = hex("0123456789abcdeffedcba9876543210");
        byte[] cipher = Sm4.encryptEcb(key, key);
        assertEquals("681edf34d206965e86b3e94f536e4246", hex(cipher, 0, 16));
        assertArrayEquals(key, Sm4.decryptEcb(key, cipher));
    }

    @Test
    void crcAndLengthMatchAppendixA() {
        String data = A1.substring(6, A1.length() - 6);
        assertEquals(A1, Hj212Format.frame(data));
        assertTrue(Hj212Format.frame(A2_1).startsWith("##0295"));
        assertTrue(Hj212Format.frame(A2_1).endsWith("2200\r\n"));
        assertTrue(Hj212Format.frame(A2_3).startsWith("##0234"));
        assertTrue(Hj212Format.frame(A2_3).endsWith("B541\r\n"));
    }

    @Test
    void flagCarriesVersion2() {
        Hj212Packet p = Hj212Format.parse(A1.substring(6, A1.length() - 6));
        assertEquals(Hj212Packet.VERSION_2025, p.version());
        assertEquals(Hj212Versions.V2025, Hj212Versions.detect(p));
        assertTrue(p.needAck());
        assertFalse(p.isSplit());
        // 应答也用 2025 版：Flag=8
        assertEquals(8, p.dataAck().getFlag());
        assertEquals(8, p.reply(Hj212Commands.REQUEST_ACK).getFlag());
        // 2025 版不做因子编码转换
        assertTrue(Hj212Versions.normalize(p, null) == p);
    }

    @Test
    void encryptionFollowsAppendixA2() {
        byte[] plain = A2_3.getBytes(StandardCharsets.UTF_8);
        byte[] cipher = Hj212Crypto.encrypt(plain, KEY);
        // 密文和明文一样长，包头和结尾的 && 不变
        assertEquals(plain.length, cipher.length);
        int cp = A2_3.indexOf("CP=&&") + 5;
        assertEquals(A2_3.substring(0, cp), new String(cipher, 0, cp, StandardCharsets.US_ASCII));
        // CP 内容 147 字节 = 9 组密文 + 3 字节明文 "g=N"，和示例 4 一样
        assertEquals("g=N&&", new String(cipher, cipher.length - 5, 5, StandardCharsets.US_ASCII));
        // 示例 4 印出来的第 2 组密文
        assertEquals("a242577abfaed881be88b72d69fc55ed", hex(cipher, cp + 16, 16));
        assertArrayEquals(plain, Hj212Crypto.decrypt(cipher, KEY));
    }

    @Test
    void encryptedFrameDecodedWithDeviceKey() {
        Hj212Packet p = Hj212Format.parse(A2_1);
        EmbeddedChannel out = new EmbeddedChannel(new Hj212Codecs.Encoder());
        out.writeOutbound(p.encryptWith(KEY));
        ByteBuf frame = out.readOutbound();
        byte[] bytes = ByteBufUtil.getBytes(frame);
        frame.release();
        // 长度和 CRC 都是明文的
        assertEquals("##0295", new String(bytes, 0, 6, StandardCharsets.US_ASCII));
        assertEquals("2200\r\n", new String(bytes, bytes.length - 6, 6, StandardCharsets.US_ASCII));
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("DataTime"));

        // 网关配了这台设备的密钥（过渡期新旧两把）：解开，标记为加密报文
        Hj212Codecs.KeyLookup keys = mn -> MN.equals(mn)
                ? Arrays.asList(Hj212Crypto.key("1111111111111111"), KEY) : new ArrayList<byte[]>();
        EmbeddedChannel in = codecChannel(keys);
        in.writeInbound(Unpooled.wrappedBuffer(bytes));
        Hj212Packet decoded = in.readInbound();
        assertNotNull(decoded);
        assertTrue(decoded.isEncrypted());
        assertEquals("3.212", decoded.getFactors().get("a01014").get("Max"));
        assertEquals("20240520210600", decoded.getParam("DataTime"));

        // 明文报文照常解析，不标记加密
        in.writeInbound(Unpooled.copiedBuffer(Hj212Format.frame(A2_1), StandardCharsets.US_ASCII));
        assertFalse(((Hj212Packet) in.readInbound()).isEncrypted());

        // 没配密钥：CRC 对不上，丢弃
        EmbeddedChannel noKey = codecChannel(null);
        noKey.writeInbound(Unpooled.wrappedBuffer(bytes));
        assertNull(noKey.readInbound());
    }

    @Test
    void onlyDataKeyAndInfoCommandsAreEncrypted() {
        assertTrue(Hj212Crypto.mustEncrypt("2011"));
        assertTrue(Hj212Crypto.mustEncrypt("2062"));
        assertTrue(Hj212Crypto.mustEncrypt("1014"));
        assertTrue(Hj212Crypto.mustEncrypt("3020"));
        assertFalse(Hj212Crypto.mustEncrypt("9014"));
        assertFalse(Hj212Crypto.mustEncrypt("1011"));
        assertEquals(16, Hj212Crypto.newKey().length());
    }

    @Test
    void refillFlag() {
        Hj212Packet p = new Hj212Packet().setQn("20240601085857223").setSt("32").setCn("2061").setPw("123456").setMn(MN)
                .setFlag(9).setRefill(true).putParam("DataTime", "20240601080000").putFactor("w01018", "Avg", "40.1");
        String data = Hj212Format.format(p);
        assertTrue(data.contains(";Flag=9;RF=1;CP=&&"));
        assertTrue(Hj212Format.parse(data).isRefill());
        assertFalse(Hj212Format.parse(A2_1).isRefill());
    }

    @Test
    void logsKeepSeparatorsAndChinese() {
        // 表 C.47：日志写在一对 // 之间，可以有中文；这里故意带上分号和逗号
        String data = "QN=20240601085857223;ST=32;CN=3020;PW=123456;MN=" + MN + ";Flag=9;"
                + "CP=&&DataTime=20240601085857;PolId=w01018;i11001-Info=//清洗管路;更换试剂,完成//&&";
        Hj212Packet p = Hj212Format.parse(data);
        assertEquals("//清洗管路;更换试剂,完成//", p.getFactors().get("i11001").get("Info"));
        assertEquals("w01018", p.getParam("PolId"));
        // 值里的网址不是日志
        Hj212Packet url = Hj212Format.parse("QN=1;ST=91;CN=8001;PW=1;MN=M;Flag=5;CP=&&FwUrl=http://127.0.0.1/a/b;FwMd5=ab&&");
        assertEquals("http://127.0.0.1/a/b", url.getParam("FwUrl"));
        assertEquals("ab", url.getParam("FwMd5"));
        // 长度按 UTF-8 字节算，解码后中文不乱
        EmbeddedChannel ch = codecChannel(null);
        ch.writeInbound(Unpooled.copiedBuffer(Hj212Format.frame(data), StandardCharsets.UTF_8));
        Hj212Packet decoded = ch.readInbound();
        assertEquals("//清洗管路;更换试剂,完成//", decoded.getFactors().get("i11001").get("Info"));
    }

    @Test
    void toleratesInconsistenciesInAppendixC() {
        // 表 C.29：BeginTime、EndTime 之间是逗号；表 C.31：字段名前多一个空格
        Hj212Packet a = Hj212Format.parse("QN=20240601085857223;ST=32;CN=2041;PW=123456;MN=" + MN + ";Flag=9;"
                + "CP=&&BeginTime=20240601000000,EndTime=20240601000000&&");
        assertEquals("20240601000000", a.getParam("EndTime"));
        Hj212Packet b = Hj212Format.parse("QN=20240601085857223;ST=31;CN=2111;PW=123456;MN=" + MN + ";Flag=9;"
                + "CP=&&DataTime=20240601011000; a01901-Avg=868&&");
        assertEquals("868", b.getFactors().get("a01901").get("Avg"));
        // 表 C.2：没激活的数采仪 MN 为空
        assertEquals("", Hj212Format.parse("QN=20240601085857223;ST=32;CN=1001;PW=123456;MN=;Flag=9;"
                + "CP=&&i23005-Info=6970000000001ABCDEFG1234&&").getMn());
    }

    @Test
    void heartbeatGetsNotifyAck() {
        // 表 C.54：心跳包 9015，上位机回 9013，QN 原样带回，不交给下游
        List<Hj212Packet> received = new ArrayList<>();
        SessionManager sessions = new SessionManager();
        EmbeddedChannel ch = serverChannel(sessions, received);
        ch.writeInbound(ascii(Hj212Format.frame("QN=20240601085857223;ST=32;CN=9015;PW=123456;MN=" + MN + ";Flag=9;CP=&&&&")));
        assertTrue(received.isEmpty());
        assertNotNull(sessions.get(MN));
        Hj212Packet ack = readOutbound(ch);
        assertEquals("QN=20240601085857223;ST=91;CN=9013;PW=123456;MN=" + MN + ";Flag=8;CP=&&&&", Hj212Format.format(ack));
    }

    @Test
    void splitPartsHandledOneByOne() {
        // 表 C.58：Flag=11，每个分包 QN 不同、都带 DataTime，每包单独应答、单独交给下游
        List<Hj212Packet> received = new ArrayList<>();
        EmbeddedChannel ch = serverChannel(new SessionManager(), received);
        ch.writeInbound(ascii(Hj212Format.frame("QN=20240601085857534;ST=32;CN=2061;PW=123456;MN=" + MN + ";Flag=11;PNUM=2;PNO=1;"
                + "CP=&&DataTime=20240601080000;w00000-Cou=63.0,w00000-Avg=17.5,w00000-Flag=N&&")));
        ch.writeInbound(ascii(Hj212Format.frame("QN=20240601085857535;ST=32;CN=2061;PW=123456;MN=" + MN + ";Flag=11;PNUM=2;PNO=2;"
                + "CP=&&DataTime=20240601080000;w01018-Cou=63.0,w01018-Avg=40.1,w01018-Flag=N&&")));
        assertEquals(2, received.size());
        assertEquals("20240601080000", received.get(1).getParam("DataTime"));
        Hj212Packet ack1 = readOutbound(ch);
        Hj212Packet ack2 = readOutbound(ch);
        assertEquals("20240601085857534", ack1.getQn());
        assertEquals("20240601085857535", ack2.getQn());
        assertEquals(8, ack2.getFlag());
        assertNull(ch.readOutbound());
    }

    @Test
    void flagsAndCodes() {
        assertEquals("闷炉", Hj212Commands.flagName("B", Hj212Packet.VERSION_2025));
        assertEquals("通讯异常", Hj212Commands.flagName("B", Hj212Packet.VERSION_2017));
        assertEquals("关键工况参数故障", Hj212Commands.flagName("Td", Hj212Packet.VERSION_2025));
        assertEquals("冷启动", Hj212Commands.flagName("Stc", Hj212Packet.VERSION_2025));
        assertTrue(Hj212Commands.isValidFlag("Sr"));
        assertFalse(Hj212Commands.isValidFlag("Td"));
        assertFalse(Hj212Commands.isValidFlag("Sd"));
        assertTrue(Hj212Factors.isFactorCode("d20105"));
        assertTrue(Hj212Factors.isFactorCode("p11a01"));
        assertTrue(Hj212Factors.isFactorCode("Leq"));
        assertFalse(Hj212Factors.isFactorCode("i23005"));
    }

    private static EmbeddedChannel codecChannel(Hj212Codecs.KeyLookup keys) {
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Hj212Codecs.addTo(c.pipeline(), keys);
            }
        });
    }

    private static EmbeddedChannel serverChannel(SessionManager sessions, List<Hj212Packet> received) {
        Hj212Server.Handler handler = new Hj212Server.Handler(sessions, (mn, pw) -> true, (s, p) -> {
            received.add(p);
            return CompletableFuture.completedFuture(null);
        });
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                Hj212Codecs.addTo(ch.pipeline());
                ch.pipeline().addLast(handler);
            }
        });
    }

    private static Hj212Packet readOutbound(EmbeddedChannel ch) {
        ByteBuf buf = ch.readOutbound();
        String frame = buf.toString(StandardCharsets.UTF_8);
        buf.release();
        return Hj212Format.parse(frame.substring(6, frame.length() - 6));
    }

    private static ByteBuf ascii(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.US_ASCII);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }
}
