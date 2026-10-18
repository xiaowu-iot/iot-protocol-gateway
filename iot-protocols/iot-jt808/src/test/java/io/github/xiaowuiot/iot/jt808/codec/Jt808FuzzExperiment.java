package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.Location;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 给 808 解码链路做模糊测试：往正常的报文流里注入各种损坏，统计每种损坏的后果。
 * <p>
 * 不是常规单元测试，手动运行，结果写在模块目录下的 fuzz-result.txt：
 * <pre>mvn -pl iot-protocols/iot-jt808 -am test -Dtest=Jt808FuzzExperiment -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
class Jt808FuzzExperiment {

    static final int N = 100_000;
    static final long SEED = 20261018L;

    static {
        // 每丢一帧解码器都会打 WARN，10 万次会把控制台刷爆，实验时关掉
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
    }

    static final java.io.PrintStream OUT = out();

    static java.io.PrintStream out() {
        try {
            return new java.io.PrintStream(new java.io.FileOutputStream(System.getProperty("fuzz.out", "fuzz-result.txt")), true, "UTF-8");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void run() {
        List<byte[]> frames = buildFrames(N);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < N; i++) {
            index.put(HexUtils.toHex(frames.get(i)), i);
        }
        OUT.printf("样本：%d 条 0x0200 位置汇报，单帧 %d~%d 字节%n", N,
                frames.stream().mapToInt(f -> f.length).min().getAsInt(),
                frames.stream().mapToInt(f -> f.length).max().getAsInt());

        clean(frames, index);
        oneByte(frames, index);
        twoBytes(frames, index);
        dropDelimiter(frames, index);
        garbage(frames, index);
        shared(frames, index);

        OUT.println();
        OUT.println("==================== 对照组：旧写法，切帧后连结尾的 7E 一起吃掉 ====================");
        variant = true;
        oneByte(frames, index);
        dropDelimiter(frames, index);
        garbage(frames, index);
        shared(frames, index);
        variant = false;
    }

    /** G：相邻两帧共用一个 7E（7E A 7E B 7E ...） */
    void shared(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED + 5);
        List<byte[]> stream = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            byte[] f = frames.get(i);
            stream.add(i == 0 ? f : java.util.Arrays.copyOfRange(f, 1, f.length));
        }
        Result res = feed(concat(stream), r, index, frames);
        OUT.printf("%n[G] 相邻帧共用分隔符：好帧收到 %d / %d，丢弃 %s，异常 %d%n", res.good, N, res.drops, res.exceptions);
    }

    // ==================== 实验 ====================

    /** A：不注入损坏，只把字节流随机切成 1~1460 字节的小块喂进去 */
    void clean(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED);
        byte[] all = concat(frames);
        for (int warm = 0; warm < 3; warm++) {
            feed(all, r, index, frames);
        }
        double[] secs = new double[5];
        Result res = null;
        for (int k = 0; k < 5; k++) {
            res = feed(all, r, index, frames);
            secs[k] = res.seconds;
        }
        java.util.Arrays.sort(secs);
        res.seconds = secs[2];
        OUT.printf("%n[A] 无损坏，随机切块：收到 %d / %d，丢弃 %s，异常 %d，单线程解码 %.0f 万帧/秒%n",
                res.good, N, res.drops, res.exceptions, N / res.seconds / 10000);
    }

    /** B：1% 的帧随机改 1 个字节（任意值，可能改成 7E / 7D） */
    void oneByte(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED + 1);
        List<byte[]> stream = new ArrayList<>();
        Map<Integer, String> what = new HashMap<>();
        int corrupted = 0;
        for (int i = 0; i < N; i++) {
            byte[] f = frames.get(i).clone();
            if (r.nextInt(100) == 0) {
                int pos = 1 + r.nextInt(f.length - 2);
                byte old = f[pos];
                byte b;
                do {
                    b = (byte) r.nextInt(256);
                } while (b == old);
                f[pos] = b;
                corrupted++;
                what.put(i, b == 0x7E ? "改成7E" : b == 0x7D ? "改成7D" : old == 0x7D ? "破坏转义" : "普通");
            }
            stream.add(f);
        }
        Result res = feed(concat(stream), r, index, frames);
        int lostClean = res.lostClean(what.keySet(), N);
        OUT.printf("%n[B] %d 帧各改 1 个字节：好帧收到 %d，被连累丢掉的好帧 %d，坏帧混进来 %d，丢弃 %s，异常 %d%n",
                corrupted, res.good, lostClean, res.undetected.size(), res.drops, res.exceptions);
        Map<String, Integer> kinds = new TreeMap<>();
        what.values().forEach(k -> kinds.merge(k, 1, Integer::sum));
        OUT.println("    损坏类型分布：" + kinds);
        OUT.println("    被连累的好帧，按它前一帧的损坏类型：" + res.collateralBy(what, N));
    }

    /** C：每一帧都随机改 2 个不同位置的字节（不改成 7E / 7D，只考验校验码） */
    void twoBytes(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED + 2);
        List<byte[]> stream = new ArrayList<>();
        Map<String, Integer> corruptedIndex = new HashMap<>();
        Map<Integer, int[]> positions = new HashMap<>();
        for (int i = 0; i < N; i++) {
            byte[] f = frames.get(i).clone();
            int p1, p2;
            do {
                p1 = 1 + r.nextInt(f.length - 2);
                p2 = 1 + r.nextInt(f.length - 2);
            } while (p1 == p2 || f[p1] == 0x7D || f[p2] == 0x7D || f[p1 - 1] == 0x7D || f[p2 - 1] == 0x7D);
            f[p1] = randomPlain(r, f[p1]);
            f[p2] = randomPlain(r, f[p2]);
            stream.add(f);
            corruptedIndex.put(HexUtils.toHex(f), i);
            positions.put(i, new int[]{p1, p2});
        }
        Result res = feed(concat(stream), r, index, frames);
        OUT.printf("%n[C] %d 帧每帧改 2 个字节：被校验码拦下 %d，校验通过混进来 %d（%.3f%%），丢弃 %s，异常 %d%n",
                N, N - res.undetected.size(), res.undetected.size(), res.undetected.size() * 100.0 / N, res.drops, res.exceptions);

        Map<String, Integer> fields = new TreeMap<>();
        Map<String, Integer> sanity = new TreeMap<>();
        int shown = 0;
        for (Jt808Message m : res.undetected) {
            Integer i = corruptedIndex.get(encode(m));
            if (i == null && !(m.getBody() instanceof Location)) {
                fields.merge("消息ID被改成 0x" + Integer.toHexString(m.getMsgId()), 1, Integer::sum);
                sanity.merge("消息ID不是0x0200", 1, Integer::sum);
                continue;
            }
            if (i == null) {
                Double km = ((Location) m.getBody()).getMileageKm();
                if (km != null && km * 10 >= 0 && km * 10 < N) {
                    i = (int) Math.round(km * 10);
                }
            }
            if (i == null) {
                fields.merge("无法对应", 1, Integer::sum);
                sanity.merge("无法对应原帧", 1, Integer::sum);
                continue;
            }
            Jt808Message orig = decodeOne(frames.get(i));
            sanity.merge(sanity(orig, m), 1, Integer::sum);
            List<String> diff = diff(orig, m);
            diff.forEach(d -> fields.merge(d, 1, Integer::sum));
            if (shown < 5) {
                shown++;
                OUT.printf("    例 %d：第 %d 帧，改了线路上第 %d、%d 字节，变化：%s%n", shown, i,
                        positions.get(i)[0], positions.get(i)[1], describe(orig, m, diff));
            }
        }
        OUT.println("    混进来的帧，被改掉的字段统计：" + fields);
        OUT.println("    再加几条常识校验，第一条命中的规则：" + sanity);
    }

    /**
     * 平台侧不需要任何额外信息就能做的检查。orig 的手机号代表鉴权时绑定在会话上的手机号，orig 的时间代表服务器当前时间
     */
    static String sanity(Jt808Message orig, Jt808Message m) {
        if (!(m.getBody() instanceof Location)) {
            return "消息ID不是0x0200";
        }
        if (!orig.getHeader().getPhone().equals(m.getHeader().getPhone())) {
            return "手机号和会话不一致";
        }
        Location l = m.getBody();
        if (l.getLatitude() < 3.8 || l.getLatitude() > 53.6 || l.getLongitude() < 73.4 || l.getLongitude() > 135.1) {
            return "经纬度不在国内";
        }
        LocalDateTime t = ((Location) orig.getBody()).getTime();
        if (l.getTime() == null || Math.abs(java.time.Duration.between(t, l.getTime()).toMinutes()) > 10) {
            return "时间和服务器差10分钟以上";
        }
        if (l.getSpeed() > 200) {
            return "速度超过200";
        }
        return "全部通过";
    }

    /** D：0.1% 的帧随机删掉开头或结尾的 7E */
    void dropDelimiter(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED + 3);
        List<byte[]> stream = new ArrayList<>();
        Map<Integer, String> what = new HashMap<>();
        for (int i = 0; i < N; i++) {
            byte[] f = frames.get(i);
            if (r.nextInt(1000) == 0) {
                boolean head = r.nextBoolean();
                byte[] g = new byte[f.length - 1];
                System.arraycopy(f, head ? 1 : 0, g, 0, g.length);
                f = g;
                what.put(i, head ? "删开头" : "删结尾");
            }
            stream.add(f);
        }
        Result res = feed(concat(stream), r, index, frames);
        OUT.printf("%n[D] %d 帧删掉一个 7E：好帧收到 %d，一共少了 %d 帧，其中完好却被连累的 %d，丢弃 %s，异常 %d%n",
                what.size(), res.good, N - res.good, res.lostClean(what.keySet(), N), res.drops, res.exceptions);
        long survived = what.keySet().stream().filter(res.goodSet::contains).count();
        Map<String, int[]> by = new TreeMap<>();
        what.forEach((i, k) -> {
            int[] c = by.computeIfAbsent(k, x -> new int[3]);
            c[0]++;
            if (res.goodSet.contains(i)) {
                c[1]++;
            }
            if (i + 1 < N && !what.containsKey(i + 1) && !res.goodSet.contains(i + 1)) {
                c[2]++;
            }
        });
        OUT.println("    损坏帧本身照样解析成功的：" + survived);
        by.forEach((k, c) -> OUT.printf("    %s %d 次：本帧仍然收到 %d 次，连累下一帧丢失 %d 次%n", k, c[0], c[1], c[2]));
    }

    /** E：1% 的帧前面插一段 1~256 字节的随机垃圾（可能含 7E） */
    void garbage(List<byte[]> frames, Map<String, Integer> index) {
        Random r = new Random(SEED + 4);
        List<byte[]> stream = new ArrayList<>();
        Map<Integer, String> what = new HashMap<>();
        int with7e = 0;
        for (int i = 0; i < N; i++) {
            if (r.nextInt(100) == 0) {
                byte[] g = new byte[1 + r.nextInt(256)];
                r.nextBytes(g);
                boolean has = false;
                for (byte b : g) {
                    has |= b == 0x7E;
                }
                with7e += has ? 1 : 0;
                what.put(i, has ? "含7E" : "不含7E");
                stream.add(g);
            }
            stream.add(frames.get(i));
        }
        Result res = feed(concat(stream), r, index, frames);
        OUT.printf("%n[E] %d 段垃圾（其中 %d 段含 7E）：好帧收到 %d / %d，丢掉 %d，混进来 %d，丢弃 %s，异常 %d%n",
                what.size(), with7e, res.good, N, N - res.good, res.undetected.size(), res.drops, res.exceptions);
        Map<String, int[]> by = new TreeMap<>();
        what.forEach((i, k) -> {
            int[] c = by.computeIfAbsent(k, x -> new int[2]);
            c[0]++;
            if (!res.goodSet.contains(i)) {
                c[1]++;
            }
        });
        by.forEach((k, c) -> OUT.printf("    垃圾%s %d 段：紧跟其后的好帧丢失 %d 次%n", k, c[0], c[1]));
    }

    // ==================== 工具 ====================

    static class Result {
        int good;
        java.util.Set<Integer> goodSet = new java.util.HashSet<>();
        List<Jt808Message> undetected = new ArrayList<>();
        Map<String, Long> drops = new TreeMap<>();
        int exceptions;
        double seconds;

        int lostClean(java.util.Set<Integer> corrupted, int n) {
            int lost = 0;
            for (int i = 0; i < n; i++) {
                if (!corrupted.contains(i) && !goodSet.contains(i)) {
                    lost++;
                }
            }
            return lost;
        }

        Map<String, Integer> collateralBy(Map<Integer, String> what, int n) {
            Map<String, Integer> m = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                if (!what.containsKey(i) && !goodSet.contains(i)) {
                    m.merge(what.getOrDefault(i - 1, "前一帧完好"), 1, Integer::sum);
                }
            }
            return m;
        }
    }

    static boolean variant;

    static Result feed(byte[] stream, Random r, Map<String, Integer> index, List<byte[]> frames) {
        Map<String, Long> before = snapshot();
        Result res = new Result();
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Jt808Codecs.addTo(c.pipeline());
                if (variant) {
                    c.pipeline().replace("jt808FrameDecoder", "jt808FrameDecoder",
                            new ConsumeEndDelimiterFrameDecoder(Jt808Codecs.DELIMITER, Jt808Codecs.ESCAPER, Jt808Codecs.MAX_FRAME_LENGTH));
                }
            }
        });
        long t0 = System.nanoTime();
        int pos = 0;
        List<Object> got = new ArrayList<>(N);
        while (pos < stream.length) {
            int len = Math.min(stream.length - pos, 1 + r.nextInt(1460));
            try {
                ch.writeInbound(Unpooled.wrappedBuffer(stream, pos, len));
            } catch (RuntimeException e) {
                res.exceptions++;
            }
            pos += len;
            Object o;
            while ((o = ch.readInbound()) != null) {
                got.add(o);
            }
        }
        res.seconds = (System.nanoTime() - t0) / 1e9;
        for (Object o : got) {
            Jt808Message m = (Jt808Message) o;
            Integer i = index.get(encode(m));
            if (i != null) {
                res.good++;
                res.goodSet.add(i);
            } else {
                res.undetected.add(m);
            }
        }
        ch.finishAndReleaseAll();
        Map<String, Long> after = snapshot();
        after.forEach((k, v) -> {
            long d = v - before.getOrDefault(k, 0L);
            if (d > 0) {
                res.drops.put(k, d);
            }
        });
        return res;
    }

    static Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        CodecStats.all().forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    static List<byte[]> buildFrames(int n) {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Jt808Codecs.addTo(c.pipeline());
            }
        });
        Random r = new Random(SEED);
        List<byte[]> frames = new ArrayList<>(n);
        LocalDateTime t = LocalDateTime.of(2026, 10, 18, 8, 0, 0);
        for (int i = 0; i < n; i++) {
            Jt808Header h = new Jt808Header();
            h.setMsgId(MessageId.LOCATION);
            h.setPhone(String.format("0138%08d", r.nextInt(1000)));
            h.setSerialNo(i & 0xFFFF);
            Location l = new Location().setAlarm(0).setStatus(0b11)
                    .setLatitude(22.5 + r.nextDouble() * 0.2).setLongitude(113.9 + r.nextDouble() * 0.3)
                    .setAltitude(20 + r.nextInt(30)).setSpeed(r.nextInt(900) / 10.0).setDirection(r.nextInt(360))
                    .setTime(t.plusSeconds(i))
                    .putExtra(Location.EXTRA_MILEAGE, (long) i)
                    .putExtra(Location.EXTRA_SATELLITES, (long) (8 + r.nextInt(10)));
            ch.writeOutbound(new Jt808Message(h, l));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ByteBuf b;
            while ((b = ch.readOutbound()) != null) {
                bos.write(ByteBufUtil.getBytes(b), 0, b.readableBytes());
                b.release();
            }
            frames.add(bos.toByteArray());
        }
        ch.finishAndReleaseAll();
        return frames;
    }

    static final EmbeddedChannel ENCODER = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
        @Override
        protected void initChannel(EmbeddedChannel c) {
            Jt808Codecs.addTo(c.pipeline());
        }
    });

    static String encode(Jt808Message m) {
        if (!(m.getBody() instanceof Location)) {
            return "msgId=" + Integer.toHexString(m.getMsgId());
        }
        try {
            ENCODER.writeOutbound(m);
        } catch (RuntimeException e) {
            ENCODER.releaseOutbound();
            return "ENCODE_FAIL:" + m.getHeader().getPhone() + ":" + ((Location) m.getBody()).getMileageKm();
        }
        StringBuilder sb = new StringBuilder();
        ByteBuf b;
        while ((b = ENCODER.readOutbound()) != null) {
            sb.append(HexUtils.toHex(b));
            b.release();
        }
        return sb.toString();
    }

    static Jt808Message decodeOne(byte[] frame) {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel c) {
                Jt808Codecs.addTo(c.pipeline());
            }
        });
        ch.writeInbound(Unpooled.wrappedBuffer(frame));
        Jt808Message m = ch.readInbound();
        ch.finishAndReleaseAll();
        return m;
    }

    static List<String> diff(Jt808Message a, Jt808Message b) {
        List<String> d = new ArrayList<>();
        Jt808Header ha = a.getHeader(), hb = b.getHeader();
        if (ha.getMsgId() != hb.getMsgId()) d.add("消息ID");
        if (!ha.getPhone().equals(hb.getPhone())) d.add(hb.getPhone().matches("[0-9]+") ? "终端手机号" : "终端手机号(非法BCD)");
        if (ha.getSerialNo() != hb.getSerialNo()) d.add("流水号");
        if (!(b.getBody() instanceof Location)) {
            d.add("消息体类型");
            return d;
        }
        Location la = a.getBody(), lb = b.getBody();
        if (la.getAlarm() != lb.getAlarm()) d.add("报警位");
        if (la.getStatus() != lb.getStatus()) d.add("状态位");
        if (la.getLatitude() != lb.getLatitude()) d.add("纬度");
        if (la.getLongitude() != lb.getLongitude()) d.add("经度");
        if (la.getAltitude() != lb.getAltitude()) d.add("高程");
        if (la.getSpeed() != lb.getSpeed()) d.add("速度");
        if (la.getDirection() != lb.getDirection()) d.add("方向");
        if (!java.util.Objects.equals(la.getTime(), lb.getTime())) d.add("时间");
        if (!java.util.Objects.equals(la.getExtras().keySet(), lb.getExtras().keySet())) {
            d.add("附加信息结构");
        } else if (!java.util.Objects.equals(la.getMileageKm(), lb.getMileageKm())) {
            d.add("里程");
        } else if (!java.util.Objects.equals(la.getSatellites(), lb.getSatellites())) {
            d.add("卫星数");
        }
        if (d.isEmpty()) d.add("其他");
        return d;
    }

    static String describe(Jt808Message a, Jt808Message b, List<String> diff) {
        StringBuilder sb = new StringBuilder();
        for (String f : diff) {
            if (f.startsWith("终端手机号")) sb.append(f).append(' ').append(a.getHeader().getPhone()).append("→").append(b.getHeader().getPhone()).append("；");
            else if (f.equals("纬度")) sb.append(f).append(' ').append(((Location) a.getBody()).getLatitude()).append("→").append(((Location) b.getBody()).getLatitude()).append("；");
            else if (f.equals("经度")) sb.append(f).append(' ').append(((Location) a.getBody()).getLongitude()).append("→").append(((Location) b.getBody()).getLongitude()).append("；");
            else if (f.equals("速度")) sb.append(f).append(' ').append(((Location) a.getBody()).getSpeed()).append("→").append(((Location) b.getBody()).getSpeed()).append("；");
            else if (f.equals("时间")) sb.append(f).append(' ').append(((Location) a.getBody()).getTime()).append("→").append(((Location) b.getBody()).getTime()).append("；");
            else if (f.equals("报警位")) sb.append(f).append(' ').append(((Location) a.getBody()).getAlarm()).append("→").append(((Location) b.getBody()).getAlarm()).append("；");
            else sb.append(f).append("；");
        }
        return sb.toString();
    }

    static byte randomPlain(Random r, byte old) {
        byte b;
        do {
            b = (byte) r.nextInt(256);
        } while (b == old || b == 0x7E || b == 0x7D);
        return b;
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return bos.toByteArray();
    }
}
