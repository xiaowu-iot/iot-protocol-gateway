package io.github.xiaowuiot.iot.simulator.web;

import io.github.xiaowuiot.protocol.codec.frame.AsciiLengthFrameDecoder;
import io.github.xiaowuiot.protocol.hj212.Hj212Commands;
import io.github.xiaowuiot.protocol.hj212.Hj212Crypto;
import io.github.xiaowuiot.protocol.hj212.Hj212Format;
import io.github.xiaowuiot.protocol.hj212.Hj212Packet;
import io.github.xiaowuiot.protocol.hj212.Hj212Split;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 网页工具背后的那台"现场机"：一条 TCP 连接，按 HJ212 2005 / 2017 / 2025 收发报文。
 * <ul>
 *     <li>上行：页面上选模板或自己写 CP 发出去，可以要求应答、补传（RF=1）、分包、加密、故意写错 CRC；
 *     要求应答的按 OverTime / ReCount 超时重发，收到 9014 / 9013 记下耗时</li>
 *     <li>下行：平台的命令全部按附录 C 的流程自动应答（9011 → 数据 → 9012，通知命令回 9013）；
 *     自动应答关掉时，页面上手动选怎么回</li>
 *     <li>自动任务：实时数据、分钟 / 小时 / 日数据、2025 版心跳</li>
 * </ul>
 * 所有状态只在一个 IO 线程里读写，页面的请求通过 {@link #call} 切到这个线程执行。
 */
final class ToolDevice {

    private static final ZoneOffset GMT8 = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter QN = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter DATA_TIME = Hj212Templates.DATA_TIME;
    private static final int MAX_HISTORY = 300;
    private static final List<Integer> MIN_INTERVALS = Arrays.asList(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30);

    /**
     * 页面发送报文的参数
     */
    static class SendRequest {
        public String cn;
        public String st;
        public String cp = "";
        public String qn;
        public boolean needAck;
        public boolean refill;
        public boolean emptyMn;
        public boolean badCrc;
        public int split;
        /**
         * auto：2025 版、有密钥、标准要求加密的命令才加密；on 有密钥就加密；off 不加密
         */
        public String encrypt = "auto";
    }

    private static final class Built {
        final byte[] frame;
        final Hj212Packet packet;

        Built(byte[] frame, Hj212Packet packet) {
            this.frame = frame;
            this.packet = packet;
        }
    }

    private static final class Pending {
        final String qn;
        final byte[] frame;
        final long eventId;
        final long firstSent = System.nanoTime();
        int attempts = 1;
        ScheduledFuture<?> timeout;

        Pending(String qn, byte[] frame, long eventId) {
            this.qn = qn;
            this.frame = frame;
            this.eventId = eventId;
        }
    }

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final EventLoop loop = group.next();
    private final EventHub hub;
    private final Bootstrap bootstrap;

    private ToolConfig cfg = new ToolConfig();
    private Channel channel;
    private boolean manualClose = true;
    private ScheduledFuture<?> tick;
    private ScheduledFuture<?> reconnectTask;

    // ===== 现场机的运行状态 =====
    private final LocalDateTime bootTime = LocalDateTime.now(GMT8).withNano(0);
    private String oldKey = "";
    private String keyCreateTime = LocalDateTime.now(GMT8).format(QN);
    private boolean realtimeOn;
    private boolean runStatusOn;
    private String maintenanceFlag = "N";
    private LocalDateTime maintenanceUntil = LocalDateTime.MIN;
    private String sampleStart = "000000";
    private String sampleCTime = "2";
    private final Map<String, String> info = new LinkedHashMap<>();
    private final List<String[]> logs = new ArrayList<>();
    private long lastRealtime;
    private long lastHeartbeat;
    private LocalDateTime lastMinute;
    private LocalDateTime lastHour;
    private long lastQn;
    private long sentCount;
    private long receivedCount;
    private final List<Pending> pending = new ArrayList<>();
    private final Map<Long, Hj212Packet> receivedPackets = new LinkedHashMap<Long, Hj212Packet>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Hj212Packet> eldest) {
            return size() > 500;
        }
    };

    ToolDevice(EventHub hub) {
        this.hub = hub;
        this.bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new AsciiLengthFrameDecoder("##", 4, 4, "\r\n", 9999))
                                .addLast(new Inbound());
                    }
                });
        info.put("i12001", "1");
        info.put("i12002", "0");
        info.put("i13001", "500");
        info.put("i13004", "168.0");
        info.put("i13006", "40");
        LocalDateTime now = LocalDateTime.now(GMT8).withNano(0);
        logs.add(new String[]{now.minusHours(3).format(DATA_TIME), "i11001", "清洗管路"});
        logs.add(new String[]{now.minusHours(2).format(DATA_TIME), "i11001", "时间校准"});
        logs.add(new String[]{now.minusMinutes(40).format(DATA_TIME), "i11001", "更换试剂，完成"});
    }

    /**
     * 在设备线程里执行
     */
    <T> T call(Callable<T> task) throws Exception {
        try {
            return loop.submit(task).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) {
                throw (Exception) c;
            }
            throw e;
        } catch (TimeoutException e) {
            throw new IllegalStateException("设备线程没有响应");
        }
    }

    void shutdown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    // ==================== 页面调用 ====================

    ToolConfig config() {
        return cfg;
    }

    void applyConfig(ToolConfig c) {
        if (c.key != null && !c.key.isEmpty() && c.key.length() != Hj212Crypto.KEY_LENGTH) {
            throw new IllegalArgumentException("密钥要 16 个字符");
        }
        if (c.key != null && !c.key.equals(cfg.key) && !cfg.key.isEmpty()) {
            oldKey = cfg.key;
        }
        if (c.realtimeAuto != cfg.realtimeAuto) {
            realtimeOn = c.realtimeAuto;
        }
        if (c.factors == null) {
            c.factors = new ArrayList<>();
        }
        c.factors.removeIf(f -> f.code == null || f.code.trim().isEmpty());
        c.factors.forEach(f -> f.code = f.code.trim());
        cfg = c;
        pushState();
    }

    void connect() {
        manualClose = false;
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
        }
        if (channel != null && channel.isActive()) {
            return;
        }
        String host = cfg.host;
        int port = cfg.port;
        hub.sys("info", "连接 " + host + ":" + port + " ……");
        bootstrap.connect(host, port).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                hub.sys("error", "连接 " + host + ":" + port + " 失败：" + f.cause().getMessage());
                scheduleReconnect();
            }
        });
    }

    void disconnect() {
        manualClose = true;
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
        }
        if (channel != null) {
            channel.close();
        }
    }

    Map<String, Object> template(String id) {
        Hj212Templates.Entry e = Hj212Templates.find(id);
        if (e == null) {
            throw new IllegalArgumentException("没有模板 " + id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("cn", e.cn);
        m.put("st", e.st != null ? e.st : cfg.st);
        m.put("needAck", e.needAck);
        m.put("emptyMn", "1001".equals(e.cn));
        m.put("note", e.note);
        m.put("cp", Hj212Templates.cp(id, cfg, now(), bootTime, currentFlag(), keyCreateTime));
        return m;
    }

    List<Map<String, Object>> preview(SendRequest q) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Built b : build(q, false)) {
            out.add(FrameInspector.inspect(b.frame, keys()).json);
        }
        return out;
    }

    List<Long> send(SendRequest q) {
        requireConnected();
        List<Long> ids = new ArrayList<>();
        for (Built b : build(q, true)) {
            ids.add(transmit(b.frame, b.packet, null));
        }
        return ids;
    }

    long sendRaw(String text, boolean fix) {
        requireConnected();
        String t = text.trim();
        byte[] frame;
        if (fix && t.startsWith("##")) {
            // 重新计算长度和 CRC：去掉 ## + 4 位长度，再去掉结尾的 4 位 CRC
            String body = t.substring(6);
            if (body.length() < 4) {
                throw new IllegalArgumentException("报文太短");
            }
            frame = Hj212Format.frameBytes(body.substring(0, body.length() - 4), null);
        } else {
            frame = FrameInspector.toFrame(t);
        }
        FrameInspector.Result r = FrameInspector.inspect(frame, keys());
        return transmit(frame, r.packet, "原始报文");
    }

    /**
     * 手动回复某条收到的报文
     */
    long reply(long eventId, String cn, int code) {
        requireConnected();
        Hj212Packet req = receivedPackets.get(eventId);
        if (req == null) {
            throw new IllegalArgumentException("找不到这条报文（只保留最近 500 条）");
        }
        Hj212Packet r;
        switch (cn) {
            case Hj212Commands.REQUEST_ACK:
                r = req.reply(cn).putParam("QnRtn", String.valueOf(code));
                break;
            case Hj212Commands.EXEC_RESULT:
                r = req.reply(cn).putParam("ExeRtn", String.valueOf(code));
                break;
            case Hj212Commands.NOTIFY_ACK:
            case Hj212Commands.DATA_ACK:
                r = req.reply(cn);
                break;
            default:
                throw new IllegalArgumentException("只能回 9011 / 9012 / 9013 / 9014");
        }
        return sendPacket(r.setPw(cfg.pw));
    }

    /**
     * 只解析，不发送
     */
    Map<String, Object> parse(String text, String key) {
        List<byte[]> k = keys();
        if (key != null && !key.trim().isEmpty()) {
            k.add(0, Hj212Crypto.key(key.trim()));
        }
        return FrameInspector.inspect(FrameInspector.toFrame(text), k).json;
    }

    Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        boolean connected = channel != null && channel.isActive();
        s.put("connected", connected);
        s.put("connecting", !manualClose && !connected);
        if (connected) {
            s.put("local", String.valueOf(channel.localAddress()));
            s.put("remote", String.valueOf(channel.remoteAddress()));
        }
        s.put("config", cfg);
        s.put("deviceTime", now().withNano(0).toString().replace('T', ' '));
        s.put("bootTime", bootTime.toString().replace('T', ' '));
        s.put("keyCreateTime", keyCreateTime);
        s.put("oldKey", oldKey);
        s.put("realtimeOn", realtimeOn);
        s.put("runStatusOn", runStatusOn);
        s.put("maintenance", now().isBefore(maintenanceUntil) ? maintenanceFlag : "");
        s.put("samplePeriod", "CstartTime=" + sampleStart + "，CTime=" + sampleCTime);
        s.put("info", info);
        List<Map<String, String>> l = new ArrayList<>();
        for (String[] x : logs) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("time", x[0]);
            m.put("code", x[1]);
            m.put("text", x[2]);
            l.add(m);
        }
        s.put("logs", l);
        s.put("pending", pending.size());
        s.put("sent", sentCount);
        s.put("received", receivedCount);
        return s;
    }

    // ==================== 组包和发送 ====================

    private List<Built> build(SendRequest q, boolean forSend) {
        if (q.cn == null || !q.cn.matches("\\d{4}")) {
            throw new IllegalArgumentException("命令编码 CN 要 4 位数字");
        }
        String st = q.st == null || q.st.trim().isEmpty() ? cfg.st : q.st.trim();
        String mn = q.emptyMn ? "" : cfg.mn;
        int flag = cfg.versionBits() << 2 | (q.needAck ? Hj212Packet.FLAG_NEED_ACK : 0);
        String qn = q.qn == null || q.qn.trim().isEmpty() ? (forSend ? nextQn() : now().format(QN)) : q.qn.trim();
        String cp = q.cp == null ? "" : q.cp.trim();
        if (cp.startsWith("&&")) {
            cp = cp.substring(2);
        }
        if (cp.endsWith("&&")) {
            cp = cp.substring(0, cp.length() - 2);
        }
        String data = header(qn, st, q.cn, cfg.pw, mn, flag, null, null, q.refill) + "CP=&&" + cp + "&&";
        Hj212Packet p = Hj212Format.parse(data);
        byte[] key = encryptKey(q.cn, q.encrypt);
        List<Built> out = new ArrayList<>();
        if (q.split > 0 && data.getBytes(StandardCharsets.UTF_8).length > q.split) {
            List<Hj212Packet> parts = Hj212Split.split(p, q.split);
            for (int i = 0; i < parts.size(); i++) {
                Hj212Packet part = parts.get(i);
                // 2025 版附录 C.57 / C.58：每个分包有自己的 QN；2017 版各包共用一个 QN
                if (cfg.version == 2025 && i > 0) {
                    part.setQn(forSend ? nextQn() : Long.toString(Long.parseLong(qn) + i));
                }
                out.add(new Built(frame(Hj212Format.format(part), key, q.badCrc), part));
            }
        } else {
            out.add(new Built(frame(data, key, q.badCrc), p));
        }
        return out;
    }

    private static String header(String qn, String st, String cn, String pw, String mn, int flag, Integer pnum, Integer pno,
                                 boolean refill) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("QN=").append(qn).append(";ST=").append(st).append(";CN=").append(cn).append(";PW=").append(pw)
                .append(";MN=").append(mn).append(";Flag=").append(flag).append(';');
        if (pnum != null) {
            sb.append("PNUM=").append(pnum).append(";PNO=").append(pno).append(';');
        }
        if (refill) {
            sb.append("RF=1;");
        }
        return sb.toString();
    }

    private static byte[] frame(String data, byte[] key, boolean badCrc) {
        byte[] f = Hj212Format.frameBytes(data, key);
        if (badCrc) {
            // 故障注入：CRC 改成一个肯定不对的值
            int i = f.length - 6;
            String crc = new String(f, i, 4, StandardCharsets.US_ASCII);
            byte[] wrong = ("0000".equals(crc) ? "FFFF" : "0000").getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(wrong, 0, f, i, 4);
        }
        return f;
    }

    private byte[] encryptKey(String cn, String mode) {
        boolean hasKey = cfg.key != null && cfg.key.length() == Hj212Crypto.KEY_LENGTH;
        if ("off".equals(mode)) {
            return null;
        }
        if ("on".equals(mode)) {
            if (!hasKey) {
                throw new IllegalArgumentException("没有配置密钥，不能加密");
            }
            return Hj212Crypto.key(cfg.key);
        }
        return cfg.version == 2025 && cfg.encrypt && hasKey && Hj212Crypto.mustEncrypt(cn) ? Hj212Crypto.key(cfg.key) : null;
    }

    /**
     * 设备自己组的包（应答、命令返回的数据、自动上报）：按配置决定加不加密
     */
    private long sendPacket(Hj212Packet p) {
        byte[] f = Hj212Format.frameBytes(Hj212Format.format(p), encryptKey(p.getCn(), "auto"));
        return transmit(f, p, null);
    }

    private void sendTemplate(String id, boolean needAck) {
        Hj212Templates.Entry e = Hj212Templates.find(id);
        SendRequest q = new SendRequest();
        q.cn = e.cn;
        q.st = e.st;
        q.needAck = needAck;
        q.emptyMn = "1001".equals(e.cn);
        q.cp = Hj212Templates.cp(id, cfg, now(), bootTime, currentFlag(), keyCreateTime);
        for (Built b : build(q, true)) {
            transmit(b.frame, b.packet, "自动");
        }
    }

    private long transmit(byte[] frame, Hj212Packet p, String tag) {
        if (channel == null || !channel.isActive()) {
            return 0;
        }
        channel.writeAndFlush(Unpooled.wrappedBuffer(frame));
        sentCount++;
        FrameInspector.Result r = FrameInspector.inspect(frame, keys());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("frame", r.json);
        if (p != null) {
            extra.put("cn", p.getCn());
            extra.put("qn", p.getQn());
        }
        if (tag != null) {
            extra.put("tag", tag);
        }
        boolean waitAck = p != null && p.needAck() && !isReply(p.getCn());
        if (waitAck) {
            extra.put("status", "等待应答");
            extra.put("statusLevel", "wait");
        }
        long id = hub.publish("up", r.crcOk ? "info" : "warn", title(p, r), extra);
        if (waitAck) {
            Pending pd = new Pending(p.getQn(), frame, id);
            pending.add(pd);
            scheduleTimeout(pd);
        }
        return id;
    }

    private void scheduleTimeout(Pending pd) {
        pd.timeout = loop.schedule(() -> {
            if (!pending.contains(pd)) {
                return;
            }
            if (pd.attempts > cfg.reCount || channel == null || !channel.isActive()) {
                pending.remove(pd);
                hub.update(pd.eventId, "超时未应答（共发送 " + pd.attempts + " 次）", "error");
                return;
            }
            pd.attempts++;
            channel.writeAndFlush(Unpooled.wrappedBuffer(pd.frame));
            sentCount++;
            hub.update(pd.eventId, "第 " + (pd.attempts - 1) + " 次重发，等待应答", "wait");
            scheduleTimeout(pd);
        }, Math.max(1, cfg.overTime), TimeUnit.SECONDS);
    }

    private static String title(Hj212Packet p, FrameInspector.Result r) {
        if (p == null) {
            return r.json.containsKey("error") ? "无法解析的报文" : "报文";
        }
        return p.getCn() + " " + Hj212Names.cn(p.getCn());
    }

    private void requireConnected() {
        if (channel == null || !channel.isActive()) {
            throw new IllegalStateException("还没有连接平台");
        }
    }

    // ==================== 收到报文 ====================

    private final class Inbound extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel = ctx.channel();
            hub.sys("ok", "已连接 " + channel.remoteAddress() + "（本地 " + channel.localAddress() + "）");
            realtimeOn = cfg.realtimeAuto;
            long now = System.nanoTime();
            // 实时数据连上 2 秒后发第一条
            lastRealtime = now - TimeUnit.SECONDS.toNanos(Math.max(0, cfg.rtdInterval - 2));
            lastHeartbeat = now;
            lastMinute = now().truncatedTo(ChronoUnit.MINUTES);
            lastHour = now().truncatedTo(ChronoUnit.HOURS);
            tick = loop.scheduleAtFixedRate(ToolDevice.this::tick, 1, 1, TimeUnit.SECONDS);
            if (cfg.version == 2025 && (cfg.mn == null || cfg.mn.isEmpty())) {
                hub.sys("info", "MN 为空：按 2025 版表 C.2 先上传硬件序号（1001），等平台用 1002 分配 MN");
                loop.schedule(() -> sendTemplate("1001", true), 1, TimeUnit.SECONDS);
            }
            if (cfg.calibrateOnConnect) {
                loop.schedule(() -> sendTemplate("1013", true), 2, TimeUnit.SECONDS);
            }
            pushState();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            hub.sys(manualClose ? "info" : "error", manualClose ? "已断开" : "连接断开");
            if (tick != null) {
                tick.cancel(false);
            }
            for (Pending pd : pending) {
                pd.timeout.cancel(false);
                hub.update(pd.eventId, "连接断开，未收到应答", "error");
            }
            pending.clear();
            channel = null;
            pushState();
            scheduleReconnect();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            onFrame(ByteBufUtil.getBytes(frame));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            hub.sys("error", "连接异常：" + cause.getMessage());
            ctx.close();
        }
    }

    private void scheduleReconnect() {
        if (manualClose || !cfg.reconnect) {
            return;
        }
        hub.sys("info", "5 秒后重连");
        reconnectTask = loop.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    void onFrame(byte[] bytes) {
        receivedCount++;
        FrameInspector.Result r = FrameInspector.inspect(bytes, keys());
        Hj212Packet p = r.packet;
        Hj212Packet head = p != null ? p : FrameInspector.header(r.plain == null ? "" : r.plain);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("frame", r.json);
        if (head != null) {
            extra.put("cn", head.getCn());
            extra.put("qn", head.getQn());
        }
        boolean request = head != null && !isReply(head.getCn());
        extra.put("replyable", request);
        long id = hub.publish("down", p != null && r.crcOk ? "info" : "error", title(head, r), extra);
        if (head != null) {
            receivedPackets.put(id, head);
        }
        if (!r.crcOk) {
            // 表 7：CRC 校验错误回 QnRtn=9
            if (request && cfg.autoReply) {
                hub.update(id, "CRC 错误，回 9011 QnRtn=9", "error");
                ack(head, Hj212Commands.QN_BAD_CRC);
            }
            return;
        }
        if (p == null) {
            return;
        }
        String cn = p.getCn();
        if (Hj212Commands.DATA_ACK.equals(cn) || Hj212Commands.NOTIFY_ACK.equals(cn)) {
            onAck(p, id);
            return;
        }
        if (isReply(cn)) {
            return;
        }
        if (cfg.dropCommands > 0) {
            cfg.dropCommands--;
            hub.update(id, "按设置假装没收到（还剩 " + cfg.dropCommands + " 条）", "warn");
            pushState();
            return;
        }
        if (!cfg.autoReply) {
            hub.update(id, "自动应答已关闭，可以手动回复", "wait");
            return;
        }
        try {
            onCommand(p, id);
        } catch (RuntimeException e) {
            hub.update(id, "处理出错：" + e.getMessage(), "error");
        }
        pushState();
    }

    private void onAck(Hj212Packet p, long id) {
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending pd = it.next();
            if (pd.qn.equals(p.getQn())) {
                it.remove();
                pd.timeout.cancel(false);
                long ms = (System.nanoTime() - pd.firstSent) / 1_000_000;
                hub.update(pd.eventId, "已收到 " + p.getCn() + "，" + ms + " ms" + (pd.attempts > 1 ? "（发送 " + pd.attempts + " 次）" : ""), "ok");
                hub.update(id, "对应上行 #" + pd.eventId, "ok");
                return;
            }
        }
        hub.update(id, "没有等待这个 QN 的上行报文", "warn");
    }

    /**
     * 9011~9014 是应答，不用再回；9015 心跳是请求
     */
    private static boolean isReply(String cn) {
        return Hj212Commands.REQUEST_ACK.equals(cn) || Hj212Commands.EXEC_RESULT.equals(cn)
                || Hj212Commands.NOTIFY_ACK.equals(cn) || Hj212Commands.DATA_ACK.equals(cn);
    }

    // ==================== 平台命令 ====================

    private void onCommand(Hj212Packet req, long id) {
        String cn = req.getCn();
        if (!Hj212Commands.SET_MN.equals(cn) && !String.valueOf(cfg.mn).equals(req.getMn())) {
            hub.update(id, "MN 不是本机，回 QnRtn=4", "warn");
            ack(req, Hj212Commands.QN_BAD_MN);
            return;
        }
        if (!String.valueOf(cfg.pw).equals(req.getPw())) {
            hub.update(id, "密码不对，回 QnRtn=3", "warn");
            ack(req, Hj212Commands.QN_BAD_PW);
            return;
        }
        String status = "已应答";
        switch (cn) {
            case Hj212Commands.SET_TIMEOUT:
                ack(req, Hj212Commands.QN_READY);
                cfg.overTime = intParam(req, "OverTime", cfg.overTime);
                cfg.reCount = intParam(req, "ReCount", cfg.reCount);
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "超时 " + cfg.overTime + " 秒，重发 " + cfg.reCount + " 次";
                break;
            case Hj212Commands.SET_MN: {
                ack(req, Hj212Commands.QN_READY);
                Map<String, String> sn = req.getFactors().get("i23005");
                if (sn == null || !cfg.hardwareSn.equals(sn.get("Info")) || req.getMn() == null || req.getMn().isEmpty()) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "硬件序号不是本机，不认领";
                    break;
                }
                cfg.mn = req.getMn();
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "MN 已设置为 " + cfg.mn;
                break;
            }
            case Hj212Commands.GET_TIME: {
                ack(req, Hj212Commands.QN_READY);
                Hj212Packet d = dataReply(req);
                if (req.getParam("PolId") != null) {
                    d.putParam("PolId", req.getParam("PolId"));
                }
                sendPacket(d.putParam("SystemTime", now().format(DATA_TIME)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.SET_TIME: {
                ack(req, Hj212Commands.QN_READY);
                LocalDateTime t = time(req.getParam("SystemTime"));
                if (t == null) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "SystemTime 格式不对";
                    break;
                }
                long before = cfg.clockOffset;
                cfg.clockOffset = Duration.between(LocalDateTime.now(GMT8), t).getSeconds();
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "校时：时钟偏差 " + before + " 秒 → " + cfg.clockOffset + " 秒";
                break;
            }
            case Hj212Commands.SECRET_KEY: {
                ack(req, Hj212Commands.QN_READY);
                String sk = req.getParam("NewSK");
                if (sk == null || sk.length() != Hj212Crypto.KEY_LENGTH) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "NewSK 不是 16 个字符";
                    break;
                }
                done(req, Hj212Commands.EXE_SUCCESS);
                // 应答发出之后再换密钥；旧密钥留着，过渡期收到的旧密钥报文还能解开
                oldKey = cfg.key;
                cfg.key = sk;
                if (req.getParam("SKCreateTime") != null) {
                    keyCreateTime = req.getParam("SKCreateTime");
                }
                status = "已换成新密钥";
                break;
            }
            case Hj212Commands.GET_RTD_INTERVAL:
                ack(req, Hj212Commands.QN_READY);
                sendPacket(dataReply(req).putParam("RtdInterval", String.valueOf(cfg.rtdInterval)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_RTD_INTERVAL: {
                ack(req, Hj212Commands.QN_READY);
                int v = intParam(req, "RtdInterval", -1);
                // 2017 版范围 30~3600 秒，2025 版放宽到 1~3600 秒
                int min = cfg.version == 2025 ? 1 : 30;
                if (v < min || v > 3600) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "RtdInterval 超出 " + min + "~3600";
                    break;
                }
                cfg.rtdInterval = v;
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "实时数据间隔改为 " + v + " 秒";
                break;
            }
            case Hj212Commands.GET_MIN_INTERVAL:
                ack(req, Hj212Commands.QN_READY);
                sendPacket(dataReply(req).putParam("MinInterval", String.valueOf(cfg.minInterval)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_MIN_INTERVAL: {
                ack(req, Hj212Commands.QN_READY);
                int v = intParam(req, "MinInterval", -1);
                if (!MIN_INTERVALS.contains(v)) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "MinInterval 只能是 " + MIN_INTERVALS;
                    break;
                }
                cfg.minInterval = v;
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "分钟数据间隔改为 " + v + " 分钟";
                break;
            }
            case Hj212Commands.SET_PASSWORD: {
                ack(req, Hj212Commands.QN_READY);
                String pw = req.getParam("NewPW");
                if (pw == null || pw.isEmpty() || pw.length() > 6) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    status = "NewPW 要 1~6 位";
                    break;
                }
                // 先用旧密码回执行结果，之后的报文用新密码
                done(req, Hj212Commands.EXE_SUCCESS);
                cfg.pw = pw;
                status = "密码已修改";
                break;
            }
            case Hj212Commands.REALTIME:
                if (req.getParam("BeginTime") != null) {
                    history(req, id);
                    return;
                }
                ack(req, Hj212Commands.QN_READY);
                realtimeOn = true;
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "开始上传实时数据（每 " + cfg.rtdInterval + " 秒）";
                break;
            case Hj212Commands.STOP_REALTIME:
                realtimeOn = false;
                notifyAck(req);
                status = "停止上传实时数据";
                break;
            case Hj212Commands.RUN_STATUS: {
                ack(req, Hj212Commands.QN_READY);
                runStatusOn = true;
                Hj212Packet d = dataReply(req);
                Hj212Templates.fill(d, cn, cfg, now().withNano(0), currentFlag(), new Random());
                sendPacket(d);
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "开始上传运行状态";
                break;
            }
            case Hj212Commands.STOP_RUN_STATUS:
                runStatusOn = false;
                notifyAck(req);
                status = "停止上传运行状态";
                break;
            case Hj212Commands.RAW_DATA:
            case Hj212Commands.DAY:
            case Hj212Commands.RUN_TIME_DAY:
            case Hj212Commands.MINUTE:
            case Hj212Commands.HOUR:
            case Hj212Commands.STANDARD_CHECK_DATA:
                history(req, id);
                return;
            case Hj212Commands.CALIBRATE:
            case Hj212Commands.CLEAN:
                if (!checkPolId(req, id)) {
                    return;
                }
                maintenance(req, Hj212Commands.CALIBRATE.equals(cn) ? "C" : "M", null);
                status = "执行中，10 秒内数据标记为 " + maintenanceFlag;
                break;
            case Hj212Commands.START_STANDARD_CHECK:
                if (!checkPolId(req, id)) {
                    return;
                }
                maintenance(req, "C", "2062");
                status = "标样核查中，10 秒后回执行结果并上传 2062";
                break;
            case Hj212Commands.SAMPLE_NOW:
            case Hj212Commands.COMPARE_SAMPLE:
            case Hj212Commands.OVER_LIMIT_SAMPLE:
                if (!checkPolId(req, id)) {
                    return;
                }
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SAMPLE_TASK:
                if (!checkPolId(req, id)) {
                    return;
                }
                done(req, Hj212Commands.EXE_SUCCESS);
                // 留样完成后上传留样信息（表 C.62、C.63）
                loop.schedule(() -> sendTemplate("3015", cfg.dataNeedAck), 3, TimeUnit.SECONDS);
                status = "3 秒后上传留样信息 3015";
                break;
            case Hj212Commands.SET_SAMPLE_PERIOD:
                if (!checkPolId(req, id)) {
                    return;
                }
                if (req.getParam("CstartTime") != null) {
                    sampleStart = req.getParam("CstartTime");
                }
                // 表 C.35 示例写 CTime，表 4 写 Ctime，两种都认
                String ct = req.getParam("CTime") != null ? req.getParam("CTime") : req.getParam("Ctime");
                if (ct != null) {
                    sampleCTime = ct;
                }
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SAMPLE_PERIOD:
                if (!checkPolId(req, id)) {
                    return;
                }
                sendPacket(dataReply(req).putParam("PolId", polId(req)).putParam("CstartTime", sampleStart)
                        .putParam("CTime", sampleCTime));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SAMPLE_OUT_TIME:
                if (!checkPolId(req, id)) {
                    return;
                }
                sendPacket(dataReply(req).putParam("PolId", polId(req)).putParam("Stime", "40"));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SN:
                if (!checkPolId(req, id)) {
                    return;
                }
                sendPacket(dataReply(req).putParam("PolId", polId(req)).putFactor(polId(req), "SN", cfg.hardwareSn));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_INFO:
                getInfo(req, id);
                return;
            case Hj212Commands.SET_INFO: {
                if (!checkPolId(req, id)) {
                    return;
                }
                List<String> changed = new ArrayList<>();
                req.getFactors().forEach((code, fields) -> {
                    if (fields.containsKey("Info")) {
                        info.put(code, fields.get("Info"));
                        changed.add(code + "=" + fields.get("Info"));
                    }
                });
                done(req, Hj212Commands.EXE_SUCCESS);
                status = "参数已修改 " + changed;
                break;
            }
            default:
                ack(req, Hj212Commands.QN_BAD_CN);
                hub.update(id, "不认识的命令，回 QnRtn=8", "warn");
                return;
        }
        hub.update(id, status, "ok");
    }

    /**
     * 提取历史数据：每个时刻一包，最后回 9012（没有数据回 ExeRtn=100）。
     * 2017 版回包的 QN 和请求一样；2025 版附录 C.24~C.26 每包是新的 QN，Flag 默认不要求应答
     */
    private void history(Hj212Packet req, long id) {
        LocalDateTime begin = time(req.getParam("BeginTime"));
        LocalDateTime end = time(req.getParam("EndTime"));
        ack(req, Hj212Commands.QN_READY);
        if (begin == null || end == null || end.isBefore(begin)) {
            done(req, Hj212Commands.EXE_BAD_CONDITION);
            hub.update(id, "BeginTime / EndTime 不对", "warn");
            return;
        }
        String cn = req.getCn();
        Duration step = step(cn);
        LocalDateTime t = align(begin, cn);
        int n = 0;
        while (!t.isAfter(end) && n < MAX_HISTORY) {
            Hj212Packet d = historyPacket(req);
            Random r = new Random(t.toEpochSecond(GMT8) * 31 + cn.hashCode());
            Hj212Templates.fill(d, cn, cfg, t, "N", r);
            sendPacket(d);
            n++;
            t = t.plus(step);
        }
        done(req, n == 0 ? Hj212Commands.EXE_NO_DATA : Hj212Commands.EXE_SUCCESS);
        hub.update(id, "返回 " + n + " 包" + (n >= MAX_HISTORY ? "（最多 " + MAX_HISTORY + " 包）" : ""), "ok");
    }

    private Duration step(String cn) {
        switch (cn) {
            case "2011":
                return Duration.ofSeconds(Math.max(1, cfg.rtdInterval));
            case "2051":
                return Duration.ofMinutes(Math.max(1, cfg.minInterval));
            case "2061":
            case "2013":
                return Duration.ofHours(1);
            default:
                return Duration.ofDays(1);
        }
    }

    /**
     * 第一个不早于 begin 的整时段
     */
    private LocalDateTime align(LocalDateTime begin, String cn) {
        LocalDateTime floor;
        switch (cn) {
            case "2011":
                return begin;
            case "2051": {
                int s = Math.max(1, cfg.minInterval);
                LocalDateTime m = begin.truncatedTo(ChronoUnit.MINUTES);
                floor = m.withMinute(m.getMinute() / s * s);
                break;
            }
            case "2061":
            case "2013":
                floor = begin.truncatedTo(ChronoUnit.HOURS);
                break;
            default:
                floor = begin.toLocalDate().atStartOfDay();
        }
        return floor.isBefore(begin) ? floor.plus(step(cn)) : floor;
    }

    private Hj212Packet historyPacket(Hj212Packet req) {
        if (cfg.version == 2025) {
            return new Hj212Packet().setQn(nextQn()).setSt(req.getSt()).setCn(req.getCn()).setPw(cfg.pw).setMn(cfg.mn)
                    .setFlag(cfg.versionBits() << 2 | (cfg.historyNeedAck ? Hj212Packet.FLAG_NEED_ACK : 0));
        }
        return dataReply(req);
    }

    /**
     * 3020 提取现场机信息：日志（i11xxx）按 BeginTime~EndTime 一条一包；状态、参数一包返回
     */
    private void getInfo(Hj212Packet req, long id) {
        if (!checkPolId(req, id)) {
            return;
        }
        String pol = req.getParam("PolId");
        String infoId = req.getParam("InfoId");
        if (infoId != null && infoId.startsWith("i11")) {
            LocalDateTime begin = time(req.getParam("BeginTime"));
            LocalDateTime end = time(req.getParam("EndTime"));
            int n = 0;
            for (String[] log : logs) {
                LocalDateTime t = time(log[0]);
                if (!log[1].equals(infoId) || begin != null && t.isBefore(begin) || end != null && t.isAfter(end)) {
                    continue;
                }
                Hj212Packet d = historyPacket(req).putParam("DataTime", log[0]);
                if (pol != null) {
                    d.putParam("PolId", pol);
                }
                sendPacket(d.putFactor(log[1], "Info", "//" + log[2] + "//"));
                n++;
            }
            done(req, n == 0 ? Hj212Commands.EXE_NO_DATA : Hj212Commands.EXE_SUCCESS);
            hub.update(id, "返回日志 " + n + " 条", "ok");
            return;
        }
        if (infoId != null && !info.containsKey(infoId)) {
            done(req, Hj212Commands.EXE_NO_DATA);
            hub.update(id, "没有 " + infoId + "，回 ExeRtn=100", "warn");
            return;
        }
        Hj212Packet d = dataReply(req).putParam("DataTime", now().format(DATA_TIME));
        if (pol != null) {
            d.putParam("PolId", pol);
        }
        info.forEach((k, v) -> {
            if (infoId == null || infoId.equals(k)) {
                d.putFactor(k, "Info", v);
            }
        });
        sendPacket(d);
        done(req, Hj212Commands.EXE_SUCCESS);
        hub.update(id, "已返回现场机信息", "ok");
    }

    /**
     * 校准、反吹、标样核查：执行期间（10 秒）实时数据的标记变成 C / M，执行完再回 9012
     */
    private void maintenance(Hj212Packet req, String flag, String thenUpload) {
        maintenanceFlag = flag;
        maintenanceUntil = now().plusSeconds(10);
        loop.schedule(() -> {
            if (channel == null || !channel.isActive()) {
                return;
            }
            done(req, Hj212Commands.EXE_SUCCESS);
            if (thenUpload != null) {
                sendTemplate(thenUpload, cfg.dataNeedAck);
            }
            pushState();
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * 控制命令带 PolId：先回 9011，不是本机的因子回"命令请求条件错误"
     */
    private boolean checkPolId(Hj212Packet req, long id) {
        ack(req, Hj212Commands.QN_READY);
        String pol = req.getParam("PolId");
        if (pol == null || Hj212Templates.hasFactor(cfg, pol)) {
            return true;
        }
        done(req, Hj212Commands.EXE_BAD_CONDITION);
        hub.update(id, "本机没有因子 " + pol + "，回 ExeRtn=3", "warn");
        return false;
    }

    private String polId(Hj212Packet req) {
        String pol = req.getParam("PolId");
        return pol != null ? pol : Hj212Templates.pollutant(cfg);
    }

    private Hj212Packet dataReply(Hj212Packet req) {
        return req.dataReply().setPw(cfg.pw).setMn(cfg.mn);
    }

    private void ack(Hj212Packet req, int qnRtn) {
        sendPacket(req.reply(Hj212Commands.REQUEST_ACK).setPw(cfg.pw).putParam("QnRtn", String.valueOf(qnRtn)));
    }

    private void done(Hj212Packet req, int exeRtn) {
        sendPacket(req.reply(Hj212Commands.EXEC_RESULT).setPw(cfg.pw).putParam("ExeRtn", String.valueOf(exeRtn)));
    }

    private void notifyAck(Hj212Packet req) {
        sendPacket(req.reply(Hj212Commands.NOTIFY_ACK).setPw(cfg.pw));
    }

    // ==================== 自动任务 ====================

    private void tick() {
        if (channel == null || !channel.isActive()) {
            return;
        }
        long nanos = System.nanoTime();
        if (realtimeOn && nanos - lastRealtime >= TimeUnit.SECONDS.toNanos(Math.max(1, cfg.rtdInterval))) {
            lastRealtime = nanos;
            sendTemplate("2011", cfg.dataNeedAck);
            if (runStatusOn) {
                sendTemplate("2021", cfg.dataNeedAck);
            }
        }
        LocalDateTime now = now();
        LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        if (!minute.equals(lastMinute)) {
            lastMinute = minute;
            if (cfg.minuteAuto && minute.getMinute() % Math.max(1, cfg.minInterval) == 0) {
                sendTemplate("2051", cfg.dataNeedAck);
            }
        }
        LocalDateTime hour = now.truncatedTo(ChronoUnit.HOURS);
        if (!hour.equals(lastHour)) {
            lastHour = hour;
            if (cfg.hourAuto) {
                sendTemplate("2061", cfg.dataNeedAck);
                if (hour.getHour() == 0) {
                    sendTemplate("2031", cfg.dataNeedAck);
                    sendTemplate("2041", cfg.dataNeedAck);
                }
            }
        }
        if (cfg.version == 2025 && cfg.heartbeatSeconds > 0
                && nanos - lastHeartbeat >= TimeUnit.SECONDS.toNanos(cfg.heartbeatSeconds)) {
            lastHeartbeat = nanos;
            sendTemplate("9015", true);
        }
    }

    // ==================== 工具 ====================

    private void pushState() {
        hub.pushState(state());
    }

    private List<byte[]> keys() {
        List<byte[]> k = new ArrayList<>();
        if (cfg.key != null && cfg.key.length() == Hj212Crypto.KEY_LENGTH) {
            k.add(Hj212Crypto.key(cfg.key));
        }
        if (oldKey != null && oldKey.length() == Hj212Crypto.KEY_LENGTH) {
            k.add(Hj212Crypto.key(oldKey));
        }
        return k;
    }

    private String currentFlag() {
        return now().isBefore(maintenanceUntil) ? maintenanceFlag : "N";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(GMT8).plusSeconds(cfg.clockOffset);
    }

    /**
     * QN 精确到毫秒，同一毫秒发两条会重复，所以保证递增
     */
    private String nextQn() {
        long ms = now().toInstant(GMT8).toEpochMilli();
        lastQn = Math.max(lastQn + 1, ms);
        return LocalDateTime.ofEpochSecond(lastQn / 1000, (int) (lastQn % 1000) * 1_000_000, GMT8).format(QN);
    }

    private static LocalDateTime time(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), DATA_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int intParam(Hj212Packet p, String key, int def) {
        try {
            String v = p.getParam(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
