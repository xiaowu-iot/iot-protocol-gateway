package io.github.xiaowuiot.iot.hj212;

import io.github.xiaowuiot.iot.hj212.codec.Hj212Codecs;
import io.github.xiaowuiot.iot.hj212.server.Hj212CommandSender;
import io.github.xiaowuiot.iot.hj212.server.Hj212Server;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hj212CommandTest {

    private static final String MN = "88888880000001";

    private Hj212Server server;
    private EventLoopGroup group;
    private Channel device;
    private final List<Hj212Packet> toListener = new CopyOnWriteArrayList<>();
    private final List<Hj212Packet> deviceReceived = new CopyOnWriteArrayList<>();
    private final AtomicInteger drop = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        int port = freePort();
        server = Hj212Server.builder().port(port).listener((s, p) -> {
            toListener.add(p);
            return CompletableFuture.completedFuture(null);
        }).build();
        server.start();
        group = new NioEventLoopGroup(1);
        device = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                Hj212Codecs.addTo(ch.pipeline());
                ch.pipeline().addLast(new TestDevice());
            }
        }).connect("127.0.0.1", port).sync().channel();
        // 第一条数据上来，平台才知道这台设备（212 没有登录）
        device.writeAndFlush(data(Hj212Commands.REALTIME, "20261018080000000", "DataTime", "20261018080000"));
        waitFor(() -> server.getSessions().get(MN) != null);
    }

    @AfterEach
    void tearDown() {
        device.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        server.stop();
    }

    @Test
    void getTimeWithDataReply() throws Exception {
        Hj212CommandSender.Result r = server.getCommandSender().send(MN, Hj212Commands.GET_TIME, null, 5).get(5, TimeUnit.SECONDS);
        assertTrue(r.isSuccess(), r.toString());
        assertEquals(1, r.data.size());
        assertEquals("20261018080510", r.data.get(0).getParam("SystemTime"));
        // 参数命令的返回数据交给等待方，不交给业务处理
        assertEquals(1, toListener.size());
    }

    @Test
    void retransmitWithSameQnWhenNoAck() throws Exception {
        server.getCommandSender().setRetry(1, 3);
        drop.set(2);
        Hj212CommandSender.Result r = server.getCommandSender().send(MN, Hj212Commands.GET_TIME, null, 5).get(10, TimeUnit.SECONDS);
        assertTrue(r.isSuccess());
        assertEquals(3, r.sendTimes);
        // 三次收到的是同一个 QN
        assertEquals(1, deviceReceived.stream().map(Hj212Packet::getQn).distinct().count());
    }

    @Test
    void rejectedRequestEndsWithoutExecResult() throws Exception {
        Hj212CommandSender.Result r = server.getCommandSender().send(MN, "1999", null, 5).get(5, TimeUnit.SECONDS);
        assertEquals(Hj212Commands.QN_BAD_CN, r.qnRtn);
        assertEquals(null, r.exeRtn);
    }

    /**
     * 提取的历史数据要交给业务处理（补进数据库），标记为 requested，而且不回 9014
     */
    @Test
    void historyDataGoesToListenerMarkedRequested() throws Exception {
        Map<String, String> range = new LinkedHashMap<>();
        range.put("BeginTime", "20261018070000");
        range.put("EndTime", "20261018070200");
        Hj212CommandSender.Result r = server.getCommandSender().send(MN, Hj212Commands.MINUTE, range, 5).get(5, TimeUnit.SECONDS);
        assertTrue(r.isSuccess());
        assertEquals(3, r.data.size());
        waitFor(() -> toListener.size() == 4);
        assertTrue(toListener.get(1).isRequested());
        Thread.sleep(200);
        assertEquals(0, deviceReceived.stream().filter(p -> Hj212Commands.DATA_ACK.equals(p.getCn())).count());
    }

    @Test
    void notifyCommandEndsWithNotifyAck() throws Exception {
        Hj212CommandSender.Result r = server.getCommandSender().send(MN, Hj212Commands.STOP_REALTIME, null, 5).get(5, TimeUnit.SECONDS);
        assertTrue(r.isSuccess());
    }

    /**
     * 现场机请求校时 1013：平台先回 9013，再下发 1012 设置时间
     */
    @Test
    void timeCalibrationRequestTriggersSetTime() throws Exception {
        Hj212Packet req = new Hj212Packet().setQn("20261018080100000").setSt("32").setCn(Hj212Commands.TIME_CALIBRATION_REQ)
                .setPw("123456").setMn(MN).setFlag(Hj212Packet.VERSION_2017 << 2 | Hj212Packet.FLAG_NEED_ACK).putParam("PolId", "w01018");
        device.writeAndFlush(req);
        waitFor(() -> deviceReceived.stream().anyMatch(p -> Hj212Commands.SET_TIME.equals(p.getCn())));
        assertTrue(deviceReceived.stream().anyMatch(p -> Hj212Commands.NOTIFY_ACK.equals(p.getCn()) && "20261018080100000".equals(p.getQn())));
        Hj212Packet set = deviceReceived.stream().filter(p -> Hj212Commands.SET_TIME.equals(p.getCn())).findFirst().get();
        assertEquals(14, set.getParam("SystemTime").length());
    }

    @Test
    void splitAndAssemble() {
        Hj212Packet big = new Hj212Packet().setQn("20261018080000000").setSt("27").setCn(Hj212Commands.MINUTE).setPw("123456")
                .setMn(MN).setFlag(Hj212Packet.VERSION_2017 << 2 | Hj212Packet.FLAG_NEED_ACK).putParam("DataTime", "20261018080000");
        for (int i = 1; i <= 40; i++) {
            String code = String.format("a99%03d", i);
            big.putFactor(code, "Min", "0.12").putFactor(code, "Avg", "0.15").putFactor(code, "Max", "0.19").putFactor(code, "Flag", "N");
        }
        List<Hj212Packet> parts = Hj212Split.split(big, Hj212Split.MAX_DATA_LENGTH);
        assertTrue(parts.size() > 1);
        Hj212Split.Assembler a = new Hj212Split.Assembler(60_000);
        Hj212Packet merged = null;
        for (int i = 0; i < parts.size(); i++) {
            Hj212Packet p = parts.get(i);
            String data = Hj212Format.format(p);
            System.out.println("分包 " + p.getPno() + "/" + p.getPnum() + "：" + data.length() + " 字节");
            assertTrue(data.getBytes(StandardCharsets.US_ASCII).length <= Hj212Split.MAX_DATA_LENGTH);
            // 每一包都带 DataTime，单独解析也是完整数据
            Hj212Packet parsed = Hj212Format.parse(data);
            assertEquals("20261018080000", parsed.getParam("DataTime"));
            assertTrue(parsed.isSplit());
            merged = a.add(parsed);
            if (i < parts.size() - 1) {
                assertEquals(null, merged);
            }
        }
        assertEquals(40, merged.getFactors().size());
        assertEquals(big.getFactors(), merged.getFactors());
        assertTrue(!merged.isSplit());
    }

    /**
     * 测试用的现场机：时间固定，分钟数据每分钟一条
     */
    class TestDevice extends SimpleChannelInboundHandler<Hj212Packet> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Hj212Packet p) {
            deviceReceived.add(p);
            if (p.getCn().startsWith("90")) {
                return;
            }
            if (drop.getAndDecrement() > 0) {
                return;
            }
            switch (p.getCn()) {
                case Hj212Commands.GET_TIME:
                    ctx.write(p.reply(Hj212Commands.REQUEST_ACK).putParam("QnRtn", "1"));
                    ctx.write(p.dataReply().putParam("SystemTime", "20261018080510"));
                    ctx.writeAndFlush(p.reply(Hj212Commands.EXEC_RESULT).putParam("ExeRtn", "1"));
                    break;
                case Hj212Commands.MINUTE:
                    ctx.write(p.reply(Hj212Commands.REQUEST_ACK).putParam("QnRtn", "1"));
                    for (int m = 0; m <= 2; m++) {
                        ctx.write(p.dataReply().putParam("DataTime", "2026101807" + String.format("%02d", m) + "00")
                                .putFactor("w01018", "Avg", "50.1").putFactor("w01018", "Flag", "N"));
                    }
                    ctx.writeAndFlush(p.reply(Hj212Commands.EXEC_RESULT).putParam("ExeRtn", "1"));
                    break;
                case Hj212Commands.STOP_REALTIME:
                    ctx.writeAndFlush(p.reply(Hj212Commands.NOTIFY_ACK));
                    break;
                case Hj212Commands.SET_TIME:
                    ctx.write(p.reply(Hj212Commands.REQUEST_ACK).putParam("QnRtn", "1"));
                    ctx.writeAndFlush(p.reply(Hj212Commands.EXEC_RESULT).putParam("ExeRtn", "1"));
                    break;
                default:
                    ctx.writeAndFlush(p.reply(Hj212Commands.REQUEST_ACK).putParam("QnRtn", String.valueOf(Hj212Commands.QN_BAD_CN)));
            }
        }
    }

    private static Hj212Packet data(String cn, String qn, String k, String v) {
        return new Hj212Packet().setQn(qn).setSt("32").setCn(cn).setPw("123456").setMn(MN)
                .setFlag(Hj212Packet.VERSION_2017 << 2).putParam(k, v).putFactor("w01018", "Rtd", "50.0");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitFor(BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertTrue(c.getAsBoolean(), "等待超时");
    }
}
