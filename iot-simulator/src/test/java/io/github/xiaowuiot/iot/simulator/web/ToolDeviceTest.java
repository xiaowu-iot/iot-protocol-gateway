package io.github.xiaowuiot.iot.simulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.iot.hj212.Hj212Crypto;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.github.xiaowuiot.iot.hj212.server.Hj212CommandSender;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟现场机和协议库里的 212 接入服务真连一次：上行、应答、加密、心跳、平台下发命令、提取历史数据
 */
class ToolDeviceTest {

    static final String KEY = "0000000000000000";
    static final String MN = "010000A8900016F000169DC0";
    static final DateTimeFormatter T = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    EventHub hub;
    ToolDevice device;
    Hj212Server server;
    final List<Hj212Packet> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = Hj212Server.builder().port(port).keys(mn -> List.of(Hj212Crypto.key(KEY)))
                .listener((session, p) -> {
                    received.add(p);
                    return CompletableFuture.completedFuture(null);
                }).build();
        server.start();
        hub = new EventHub(new ObjectMapper());
        device = new ToolDevice(hub);
        ToolConfig c = new ToolConfig();
        c.port = port;
        c.version = 2025;
        c.key = KEY;
        c.reconnect = false;
        device.call(() -> {
            device.applyConfig(c);
            device.connect();
            return null;
        });
        await(() -> Boolean.TRUE.equals(device.call(device::state).get("connected")));
    }

    @AfterEach
    void tearDown() {
        device.shutdown();
        server.stop();
    }

    @Test
    void uplinkIsEncryptedAndAcknowledged() throws Exception {
        ToolDevice.SendRequest q = new ToolDevice.SendRequest();
        q.cn = "2011";
        q.needAck = true;
        q.cp = (String) device.call(() -> device.template("2011")).get("cp");
        device.call(() -> device.send(q));
        await(() -> received.size() == 1);
        assertTrue(received.get(0).isEncrypted());
        assertEquals(2, received.get(0).version());
        await(() -> hasStatus("已收到 9014"));

        // 心跳回 9013，不交给下游
        ToolDevice.SendRequest hb = new ToolDevice.SendRequest();
        hb.cn = "9015";
        hb.needAck = true;
        device.call(() -> device.send(hb));
        await(() -> hasStatus("已收到 9013"));
        assertEquals(1, received.size());
    }

    @Test
    void answersPlatformCommands() throws Exception {
        // 212 没有注册，平台收到第一条报文才认为设备上线
        ToolDevice.SendRequest hb = new ToolDevice.SendRequest();
        hb.cn = "9015";
        hb.needAck = true;
        device.call(() -> device.send(hb));
        await(() -> hasStatus("已收到 9013"));

        Hj212CommandSender sender = server.getCommandSender();
        Hj212CommandSender.Result r = sender.send(MN, "1011", Map.of("PolId", "w01018"), 5).get(15, TimeUnit.SECONDS);
        assertTrue(r.isSuccess(), r.toString());
        assertNotNull(r.data.get(0).getParam("SystemTime"));

        // 2025 版提取小时数据：每包新 QN，平台按"同 CN、等数据中的请求"归属到这次提取；
        // 回包要求应答（附录 C.56），平台逐包回 9014
        device.call(() -> {
            device.config().historyNeedAck = true;
            return null;
        });
        LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8)).withMinute(0).withSecond(0).withNano(0);
        Hj212CommandSender.Result h = sender.send(MN, "2061",
                Map.of("BeginTime", now.minusHours(3).format(T), "EndTime", now.minusHours(1).format(T)), 10).get(15, TimeUnit.SECONDS);
        assertTrue(h.isSuccess(), h.toString());
        assertEquals(3, h.data.size());
        assertNotEquals(h.qn, h.data.get(0).getQn());
        await(() -> received.stream().filter(p -> "2061".equals(p.getCn()) && p.isRequested()).count() == 3);
        await(() -> hub.snapshot().stream().filter(e -> String.valueOf(e.get("status")).startsWith("已收到 9014")).count() == 3);

        // 改密码：之后的应答用新密码
        Hj212CommandSender.Result pw = sender.send(MN, "1072", Map.of("NewPW", "654321"), 5).get(15, TimeUnit.SECONDS);
        assertTrue(pw.isSuccess(), pw.toString());
        assertEquals("654321", device.call(() -> device.config().pw));

        // 不认识的因子：9011 准备执行，9012 条件错误
        Hj212CommandSender.Result bad = sender.send(MN, "3012", Map.of("PolId", "w99999"), 5).get(15, TimeUnit.SECONDS);
        assertFalse(bad.isSuccess());
        assertEquals(3, bad.exeRtn);
    }

    @Test
    void splitPartsGetTheirOwnQnIn2025() throws Exception {
        ToolDevice.SendRequest q = new ToolDevice.SendRequest();
        q.cn = "2061";
        q.cp = (String) device.call(() -> device.template("2061")).get("cp");
        q.split = 200;
        q.encrypt = "off";
        List<Map<String, Object>> frames = device.call(() -> device.preview(q));
        assertTrue(frames.size() > 1);
        Object qn1 = packet(frames.get(0)).get("qn");
        Object qn2 = packet(frames.get(1)).get("qn");
        assertNotEquals(qn1, qn2);
        assertEquals(10, packet(frames.get(0)).get("flag"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> packet(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("packet");
    }

    private boolean hasStatus(String prefix) {
        return hub.snapshot().stream().anyMatch(e -> String.valueOf(e.get("status")).startsWith(prefix));
    }

    private static void await(Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (cond.call()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待超时");
    }
}
