package io.github.xiaowuiot.iot.simulator.hj212;

import io.github.xiaowuiot.iot.hj212.Hj212Commands;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;
import io.github.xiaowuiot.iot.hj212.codec.Hj212Codecs;
import io.github.xiaowuiot.iot.simulator.SimulatorStats;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 一台模拟的废水数采仪（现场机）：定时上报 COD、氨氮、pH 的实时数据（2011），并响应平台的全部命令。
 * <ul>
 *     <li>收到命令先回 9011（准备执行 / 拒绝），有数据的回数据，最后回 9012 执行结果</li>
 *     <li>存了最近 3 小时的分钟数据、2 天的小时数据、7 天的日数据，供平台提取</li>
 *     <li>时钟有偏差（默认慢 95 秒），上线后发 1013 请求校时</li>
 *     <li>校准、反吹期间，实时数据的标记变成 C（校准）、M（维护）</li>
 * </ul>
 * 和 808 终端一样，所有状态只在这条连接的 IO 线程里读写。
 */
@ChannelHandler.Sharable
public class Hj212Device extends SimpleChannelInboundHandler<Hj212Packet> {

    private static final Logger log = LoggerFactory.getLogger(Hj212Device.class);

    private static final DateTimeFormatter QN = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneOffset GMT8 = ZoneOffset.ofHours(8);
    private static final String[] FACTORS = {"w01018", "w21003", "w01001"};

    private final String mn;
    private String password;
    private final Bootstrap bootstrap;
    private final InetSocketAddress remote;
    private final SimulatorStats stats;
    private final boolean verbose;
    private final Map<String, Long> sendTimes = new HashMap<>();

    private Channel channel;
    private ScheduledFuture<?> reportTask;
    private volatile boolean stopped;
    private boolean online;
    private double cod = 50;
    private double nh3n = 1.5;
    private double ph = 7.2;

    // ===== 现场机参数 =====
    private int rtdIntervalSeconds;
    private int minIntervalMinutes = 1;
    private int overTime = 5;
    private int reCount = 3;
    private boolean realtimeOn = true;
    private long clockOffsetSeconds;
    private int dropRequests;
    private final LocalDateTime bootTime;
    private LocalDateTime maintenanceUntil = LocalDateTime.MIN;
    private String maintenanceFlag = "N";
    private String sampleStart = "000000";
    private int samplePeriodHours = 2;
    private final Map<String, Map<String, String>> info = new HashMap<>();
    private boolean v2005;
    private boolean extraFactors;

    // ===== 历史数据：时间 → 因子 → 字段 → 值 =====
    private final TreeMap<LocalDateTime, Map<String, Map<String, String>>> minutes = new TreeMap<>();
    private final TreeMap<LocalDateTime, Map<String, Map<String, String>>> hours = new TreeMap<>();
    private final TreeMap<LocalDateTime, Map<String, Map<String, String>>> days = new TreeMap<>();

    public Hj212Device(String mn, String password, int intervalSeconds, Bootstrap bootstrap,
                       InetSocketAddress remote, SimulatorStats stats, boolean verbose) {
        this.mn = mn;
        this.password = password;
        this.rtdIntervalSeconds = intervalSeconds;
        this.bootstrap = bootstrap;
        this.remote = remote;
        this.stats = stats;
        this.verbose = verbose;
        this.bootTime = now().minusHours(5).withNano(0);
        generateHistory();
        for (String f : FACTORS) {
            Map<String, String> m = new LinkedHashMap<>();
            // 信息编码的含义见标准附录，这里只演示提取 / 设置的交互
            m.put("i13001", f.equals("w01001") ? "14" : "500");
            m.put("i13004", "1");
            m.put("i12001", "0");
            info.put(f, m);
        }
    }

    /**
     * @param clockOffsetSeconds 现场机时钟偏差，负数表示慢
     * @param dropRequests       前几条平台命令假装没收到（模拟丢包），看平台会不会重发
     */
    public void setFaults(long clockOffsetSeconds, int dropRequests) {
        this.clockOffsetSeconds = clockOffsetSeconds;
        this.dropRequests = dropRequests;
    }

    /**
     * @param v2005        按 2005 版上报：Flag 里没有版本号，因子编码是 011 / 060 / 001
     * @param extraFactors 多报两个标准里没有的因子：w99001（和平台约定的自定义因子）、w88123（没跟任何人约定过）
     */
    public void setVariant(boolean v2005, boolean extraFactors) {
        this.v2005 = v2005;
        this.extraFactors = extraFactors;
    }

    public void connect() {
        if (stopped) {
            return;
        }
        Hj212Device self = this;
        bootstrap.clone().handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                Hj212Codecs.addTo(ch.pipeline());
                ch.pipeline().addLast(self);
            }
        }).connect(remote).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                stats.connectFailures.increment();
                reconnectLater(f.channel());
            }
        });
    }

    public void stop() {
        stopped = true;
        if (channel != null) {
            channel.close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        stats.connected.incrementAndGet();
        // 212 没有登录，连上就算在线，第一条数据随机延迟后发出
        online = true;
        stats.online.incrementAndGet();
        scheduleReport(ThreadLocalRandom.current().nextLong(rtdIntervalSeconds * 1000L));
        if (clockOffsetSeconds != 0) {
            // 上线后请求校时
            channel.eventLoop().schedule(this::requestTimeCalibration, 2, TimeUnit.SECONDS);
        }
    }

    private void scheduleReport(long delayMillis) {
        if (reportTask != null) {
            reportTask.cancel(false);
        }
        reportTask = channel.eventLoop().scheduleAtFixedRate(this::report, delayMillis, rtdIntervalSeconds * 1000L,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stats.connected.decrementAndGet();
        stats.disconnects.increment();
        if (online) {
            online = false;
            stats.online.decrementAndGet();
        }
        if (reportTask != null) {
            reportTask.cancel(false);
        }
        sendTimes.clear();
        reconnectLater(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Hj212Packet p) {
        if (verbose) {
            log.info("{} 收到 {}", mn, p);
        }
        String cn = p.getCn();
        if (Hj212Commands.DATA_ACK.equals(cn) || Hj212Commands.NOTIFY_ACK.equals(cn)) {
            Long t = sendTimes.remove(p.getQn());
            if (t != null) {
                stats.responses.increment();
                stats.latencyMicros.add((System.nanoTime() - t) / 1000);
            }
            return;
        }
        if (dropRequests > 0) {
            dropRequests--;
            log.info("{} 假装没收到 {}（QN={}），不回应答", mn, cn, p.getQn());
            return;
        }
        onCommand(p);
    }

    // ==================== 命令处理 ====================

    private void onCommand(Hj212Packet req) {
        if (!mn.equals(req.getMn())) {
            ack(req, Hj212Commands.QN_BAD_MN);
            return;
        }
        if (!password.equals(req.getPw())) {
            log.info("{} 命令 {} 的密码不对，拒绝", mn, req.getCn());
            ack(req, Hj212Commands.QN_BAD_PW);
            return;
        }
        String cn = req.getCn();
        log.info("{} 收到命令 {} {}", mn, cn, req.getParams());
        switch (cn) {
            case Hj212Commands.SET_TIMEOUT:
                ack(req, Hj212Commands.QN_READY);
                overTime = intParam(req, "OverTime", overTime);
                reCount = intParam(req, "ReCount", reCount);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_TIME:
                ack(req, Hj212Commands.QN_READY);
                send(req.dataReply().putParam("SystemTime", now().format(DATA_TIME)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_TIME: {
                ack(req, Hj212Commands.QN_READY);
                LocalDateTime t = time(req.getParam("SystemTime"));
                if (t == null) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    break;
                }
                long before = clockOffsetSeconds;
                clockOffsetSeconds = Duration.between(LocalDateTime.now(GMT8), t).getSeconds();
                log.info("{} 校时：时钟偏差 {} 秒 → {} 秒", mn, before, clockOffsetSeconds);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.GET_RTD_INTERVAL:
                ack(req, Hj212Commands.QN_READY);
                send(req.dataReply().putParam("RtdInterval", String.valueOf(rtdIntervalSeconds)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_RTD_INTERVAL: {
                ack(req, Hj212Commands.QN_READY);
                int v = intParam(req, "RtdInterval", -1);
                // 标准对实时数据间隔有取值范围要求（以原文为准），这里只拒绝明显不合理的值
                if (v < 1 || v > 3600) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    break;
                }
                rtdIntervalSeconds = v;
                scheduleReport(v * 1000L);
                log.info("{} 实时数据间隔改为 {} 秒", mn, v);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.GET_MIN_INTERVAL:
                ack(req, Hj212Commands.QN_READY);
                send(req.dataReply().putParam("MinInterval", String.valueOf(minIntervalMinutes)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_MIN_INTERVAL:
                ack(req, Hj212Commands.QN_READY);
                minIntervalMinutes = intParam(req, "MinInterval", minIntervalMinutes);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_PASSWORD: {
                ack(req, Hj212Commands.QN_READY);
                String newPw = req.getParam("NewPW");
                if (newPw == null || newPw.isEmpty()) {
                    done(req, Hj212Commands.EXE_BAD_CONDITION);
                    break;
                }
                // 先用旧密码回执行结果，之后的报文才用新密码
                done(req, Hj212Commands.EXE_SUCCESS);
                password = newPw;
                log.info("{} 访问密码已修改", mn);
                break;
            }
            case Hj212Commands.REALTIME:
                ack(req, Hj212Commands.QN_READY);
                realtimeOn = true;
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.STOP_REALTIME:
                // "停止察看"是通知类命令：回 9013 通知应答，没有 9011 / 9012
                realtimeOn = false;
                log.info("{} 停止上报实时数据", mn);
                send(req.reply(Hj212Commands.NOTIFY_ACK));
                break;
            case Hj212Commands.STOP_RUN_STATUS:
                send(req.reply(Hj212Commands.NOTIFY_ACK));
                break;
            case Hj212Commands.RUN_STATUS: {
                ack(req, Hj212Commands.QN_READY);
                Hj212Packet d = req.dataReply().putParam("DataTime", now().format(DATA_TIME));
                for (String f : FACTORS) {
                    // RS：0 停运 1 运行 2 校准 3 维护 4 报警 5 反吹
                    d.putFactor(f, "RS", now().isBefore(maintenanceUntil) ? ("C".equals(maintenanceFlag) ? "2" : "5") : "1");
                }
                send(d);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.MINUTE:
            case Hj212Commands.HOUR:
            case Hj212Commands.DAY:
                history(req);
                break;
            case Hj212Commands.RUN_TIME_DAY: {
                ack(req, Hj212Commands.QN_READY);
                Hj212Packet d = req.dataReply().putParam("DataTime", now().toLocalDate().atStartOfDay().format(DATA_TIME));
                for (String f : FACTORS) {
                    d.putFactor(f, "RT", "23.5");
                }
                send(d);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.RESTART_TIME:
                ack(req, Hj212Commands.QN_READY);
                send(req.dataReply().putParam("DataTime", now().format(DATA_TIME)).putParam("RestartTime", bootTime.format(DATA_TIME)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.CALIBRATE:
            case Hj212Commands.CLEAN:
                maintenance(req, Hj212Commands.CALIBRATE.equals(cn) ? "C" : "M", Hj212Commands.CALIBRATE.equals(cn) ? "校准" : "反吹");
                break;
            case Hj212Commands.SAMPLE_NOW:
            case Hj212Commands.COMPARE_SAMPLE:
            case Hj212Commands.OVER_LIMIT_SAMPLE:
                if (!knownPolId(req)) {
                    break;
                }
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.SET_SAMPLE_PERIOD:
                if (!knownPolId(req)) {
                    break;
                }
                sampleStart = req.getParam("CstartTime") != null ? req.getParam("CstartTime") : sampleStart;
                samplePeriodHours = intParam(req, "CTime", samplePeriodHours);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SAMPLE_PERIOD:
                if (!knownPolId(req)) {
                    break;
                }
                send(req.dataReply().putParam("PolId", req.getParam("PolId")).putParam("CstartTime", sampleStart)
                        .putParam("CTime", String.valueOf(samplePeriodHours)));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SAMPLE_OUT_TIME:
                if (!knownPolId(req)) {
                    break;
                }
                send(req.dataReply().putParam("PolId", req.getParam("PolId")).putParam("Stime", "30"));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            case Hj212Commands.GET_SN: {
                if (!knownPolId(req)) {
                    break;
                }
                String pol = req.getParam("PolId");
                send(req.dataReply().putParam("PolId", pol).putFactor(pol, "SN", "SN" + mn.substring(mn.length() - 6) + pol));
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.GET_INFO: {
                if (!knownPolId(req)) {
                    break;
                }
                String pol = req.getParam("PolId");
                String infoId = req.getParam("InfoId");
                Map<String, String> items = info.get(pol);
                if (infoId != null && !items.containsKey(infoId)) {
                    done(req, Hj212Commands.EXE_NO_DATA);
                    break;
                }
                Hj212Packet d = req.dataReply().putParam("DataTime", now().format(DATA_TIME)).putParam("PolId", pol);
                items.forEach((k, v) -> {
                    if (infoId == null || infoId.equals(k)) {
                        d.putFactor(k, "Info", v);
                    }
                });
                send(d);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            case Hj212Commands.SET_INFO: {
                if (!knownPolId(req)) {
                    break;
                }
                Map<String, String> items = info.get(req.getParam("PolId"));
                req.getFactors().forEach((code, fields) -> {
                    if (fields.containsKey("Info")) {
                        items.put(code, fields.get("Info"));
                    }
                });
                log.info("{} 现场机信息改为 {}", mn, items);
                done(req, Hj212Commands.EXE_SUCCESS);
                break;
            }
            default:
                log.info("{} 不认识命令 {}", mn, cn);
                ack(req, Hj212Commands.QN_BAD_CN);
        }
    }

    /**
     * 提取历史数据：每个时刻一条数据包（CN、QN 和请求一样），最后回执行结果。没有数据时 ExeRtn=100
     */
    private void history(Hj212Packet req) {
        LocalDateTime begin = time(req.getParam("BeginTime"));
        LocalDateTime end = time(req.getParam("EndTime"));
        if (begin == null || end == null || end.isBefore(begin)) {
            ack(req, Hj212Commands.QN_READY);
            done(req, Hj212Commands.EXE_BAD_CONDITION);
            return;
        }
        ack(req, Hj212Commands.QN_READY);
        TreeMap<LocalDateTime, Map<String, Map<String, String>>> store = Hj212Commands.MINUTE.equals(req.getCn()) ? minutes
                : Hj212Commands.HOUR.equals(req.getCn()) ? hours : days;
        int n = 0;
        for (Map.Entry<LocalDateTime, Map<String, Map<String, String>>> e : store.subMap(begin, true, end, true).entrySet()) {
            Hj212Packet d = req.dataReply().putParam("DataTime", e.getKey().format(DATA_TIME));
            e.getValue().forEach((f, fields) -> fields.forEach((k, v) -> d.putFactor(f, k, v)));
            send(d);
            n++;
        }
        log.info("{} 提取 {} 历史数据 {} ~ {}，返回 {} 条", mn, req.getCn(), begin, end, n);
        done(req, n == 0 ? Hj212Commands.EXE_NO_DATA : Hj212Commands.EXE_SUCCESS);
    }

    /**
     * 校准 / 反吹：执行期间（20 秒）实时数据的标记变成 C / M，执行完再回 9012
     */
    private void maintenance(Hj212Packet req, String flag, String what) {
        if (!knownPolId(req)) {
            return;
        }
        maintenanceFlag = flag;
        maintenanceUntil = now().plusSeconds(20);
        log.info("{} 开始{}（{}），20 秒内数据标记为 {}", mn, what, req.getParam("PolId"), flag);
        channel.eventLoop().schedule(() -> {
            log.info("{} {}完成", mn, what);
            done(req, Hj212Commands.EXE_SUCCESS);
        }, 20, TimeUnit.SECONDS);
    }

    /**
     * 控制命令都带 PolId（对哪个因子的仪器操作）。先回 9011，因子不存在就回"命令请求条件错误"
     */
    private boolean knownPolId(Hj212Packet req) {
        ack(req, Hj212Commands.QN_READY);
        String pol = req.getParam("PolId");
        for (String f : FACTORS) {
            if (f.equals(pol)) {
                return true;
            }
        }
        done(req, Hj212Commands.EXE_BAD_CONDITION);
        return false;
    }

    private void ack(Hj212Packet req, int qnRtn) {
        send(req.reply(Hj212Commands.REQUEST_ACK).putParam("QnRtn", String.valueOf(qnRtn)));
    }

    private void done(Hj212Packet req, int exeRtn) {
        send(req.reply(Hj212Commands.EXEC_RESULT).putParam("ExeRtn", String.valueOf(exeRtn)));
    }

    private void send(Hj212Packet p) {
        stats.sent.increment();
        // 回复的密码用现场机自己当前的密码
        p.setPw(password);
        channel.writeAndFlush(p);
    }

    private void requestTimeCalibration() {
        String qn = now().format(QN);
        sendTimes.put(qn, System.nanoTime());
        log.info("{} 请求校时，当前现场机时间 {}（偏差 {} 秒）", mn, now().withNano(0), clockOffsetSeconds);
        send(new Hj212Packet().setQn(qn).setSt("32").setCn(Hj212Commands.TIME_CALIBRATION_REQ).setPw(password).setMn(mn)
                .setFlag(Hj212Packet.VERSION_2017 << 2 | Hj212Packet.FLAG_NEED_ACK)
                .putParam("PolId", "w01018"));
    }

    // ==================== 实时数据 ====================

    private void report() {
        if (!realtimeOn) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // 在合理范围内随机游走
        cod = clamp(cod + r.nextDouble(-3, 3), 10, 120);
        nh3n = clamp(nh3n + r.nextDouble(-0.2, 0.2), 0.1, 5);
        ph = clamp(ph + r.nextDouble(-0.1, 0.1), 6.0, 9.0);
        LocalDateTime now = now();
        String flag = now.isBefore(maintenanceUntil) ? maintenanceFlag : "N";
        String qn = now.format(QN);
        Hj212Packet p = new Hj212Packet()
                .setQn(qn).setSt("32").setCn(Hj212Packet.CN_REALTIME).setPw(password).setMn(mn)
                .setFlag((v2005 ? 0 : Hj212Packet.VERSION_2017 << 2) | Hj212Packet.FLAG_NEED_ACK)
                .putParam("DataTime", now.format(DATA_TIME))
                .putFactor(v2005 ? "011" : "w01018", "Rtd", fmt(cod, 1)).putFactor(v2005 ? "011" : "w01018", "Flag", flag)
                .putFactor(v2005 ? "060" : "w21003", "Rtd", fmt(nh3n, 2)).putFactor(v2005 ? "060" : "w21003", "Flag", flag)
                .putFactor(v2005 ? "001" : "w01001", "Rtd", fmt(ph, 2)).putFactor(v2005 ? "001" : "w01001", "Flag", flag);
        if (extraFactors) {
            p.putFactor("w99001", "Rtd", fmt(1.2 + r.nextDouble(-0.05, 0.05), 2)).putFactor("w99001", "Flag", "N")
                    .putFactor("w88123", "Rtd", fmt(r.nextDouble(3, 4), 1)).putFactor("w88123", "Flag", "N");
        }
        sendTimes.put(qn, System.nanoTime());
        send(p);
    }

    /**
     * 生成历史数据：最近 3 小时的分钟数据、2 天的小时数据、7 天的日数据。
     * 其中有一段"停运"（F）、一段"维护"（M），平台计算均值时要剔除
     */
    private void generateHistory() {
        LocalDateTime nowMin = LocalDateTime.now(GMT8).withSecond(0).withNano(0);
        for (int i = 180; i >= 1; i--) {
            LocalDateTime t = nowMin.minusMinutes(i);
            String flag = i >= 100 && i < 110 ? "F" : i >= 40 && i < 43 ? "M" : "N";
            minutes.put(t, record(flag));
        }
        LocalDateTime nowHour = nowMin.withMinute(0);
        for (int i = 48; i >= 1; i--) {
            hours.put(nowHour.minusHours(i), record("N"));
        }
        LocalDateTime today = nowMin.toLocalDate().atStartOfDay();
        for (int i = 7; i >= 1; i--) {
            days.put(today.minusDays(i), record("N"));
        }
    }

    private static Map<String, Map<String, String>> record(String flag) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        double codAvg = r.nextDouble(35, 65);
        m.put("w01018", fields(codAvg, codAvg - r.nextDouble(2, 8), codAvg + r.nextDouble(2, 8), 1, flag));
        double n = r.nextDouble(0.8, 2.5);
        m.put("w21003", fields(n, n - 0.2, n + 0.3, 2, flag));
        double p = r.nextDouble(6.8, 7.6);
        m.put("w01001", fields(p, p - 0.1, p + 0.1, 2, flag));
        return m;
    }

    private static Map<String, String> fields(double avg, double min, double max, int scale, String flag) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Min", fmt(min, scale));
        f.put("Avg", fmt(avg, scale));
        f.put("Max", fmt(max, scale));
        f.put("Flag", flag);
        return f;
    }

    // ==================== 工具 ====================

    private LocalDateTime now() {
        return LocalDateTime.now(GMT8).plusSeconds(clockOffsetSeconds);
    }

    private static LocalDateTime time(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, DATA_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int intParam(Hj212Packet p, String key, int def) {
        try {
            String v = p.getParam(key);
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void reconnectLater(Channel ch) {
        if (!stopped) {
            ch.eventLoop().schedule(this::connect, ThreadLocalRandom.current().nextLong(1000, 5000), TimeUnit.MILLISECONDS);
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String fmt(double v, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", v);
    }
}
