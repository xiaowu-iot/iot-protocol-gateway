package io.github.xiaowuiot.iot.hj212;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分包和组包。
 * <p>
 * 2017 版对数据段长度有上限（以标准原文为准，常见的实现按 1024 字节），因子多的时候（比如 VOCs 监测几十个因子，
 * 或者 3020 提取一大串现场机信息）一包装不下，要拆成多包：每包都有完整的包头，Flag 的 bit1 置 1，
 * 带上 PNUM（总包数）和 PNO（包号，从 1 开始）。
 * <p>
 * 拆的时候按 CP 的"组"拆（一个因子的所有字段是一组），不能把一组拆到两包里；
 * DataTime 这类公共参数每包都带，这样每一包单独拿出来也是完整、可解析的数据。
 */
public final class Hj212Split {

    public static final int MAX_DATA_LENGTH = 1024;

    private Hj212Split() {
    }

    /**
     * 拆包。不超过上限时原样返回（只有一包，不置分包标志）
     */
    public static List<Hj212Packet> split(Hj212Packet p, int maxDataLength) {
        List<Hj212Packet> result = new ArrayList<>();
        if (length(Hj212Format.format(p)) <= maxDataLength) {
            result.add(p);
            return result;
        }
        Iterator<Map.Entry<String, Map<String, String>>> factors = p.getFactors().entrySet().iterator();
        Hj212Packet current = null;
        while (factors.hasNext()) {
            Map.Entry<String, Map<String, String>> f = factors.next();
            if (current == null) {
                current = emptyPart(p);
            }
            Hj212Packet trial = copy(current);
            f.getValue().forEach((k, v) -> trial.putFactor(f.getKey(), k, v));
            // 按最长的 PNUM / PNO（各 4 位）估算长度，填上真实值之后只会更短
            trial.setPnum(9999).setPno(9999);
            if (length(Hj212Format.format(trial)) > maxDataLength && !current.getFactors().isEmpty()) {
                result.add(current);
                current = emptyPart(p);
                Hj212Packet c = current;
                f.getValue().forEach((k, v) -> c.putFactor(f.getKey(), k, v));
            } else {
                trial.setPnum(null).setPno(null);
                current = trial;
            }
        }
        if (current != null) {
            result.add(current);
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setPnum(result.size()).setPno(i + 1);
        }
        return result;
    }

    private static Hj212Packet emptyPart(Hj212Packet p) {
        Hj212Packet part = new Hj212Packet().setQn(p.getQn()).setSt(p.getSt()).setCn(p.getCn()).setPw(p.getPw())
                .setMn(p.getMn()).setFlag(p.getFlag() | Hj212Packet.FLAG_SPLIT).setRefill(p.isRefill());
        p.getParams().forEach(part::putParam);
        return part;
    }

    private static Hj212Packet copy(Hj212Packet p) {
        Hj212Packet c = emptyPart(p).setFlag(p.getFlag());
        p.getFactors().forEach((code, fields) -> fields.forEach((k, v) -> c.putFactor(code, k, v)));
        return c;
    }

    private static int length(String data) {
        return data.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 组包：同一个 MN + QN + CN 的分包收齐后合并成一包。每台设备、每次交互一个缓冲，
     * 超过 timeoutMillis 没收齐的丢弃（分包丢了一包，整组数据就不完整了）
     * <p>
     * 2025 版不一样：附录 C 的拆分包示例里每一包的 QN 都不同（…534、…535），每包都带 DataTime，单独就能解析入库，
     * 没法也不需要按 QN 合并，所以 2025 版的分包原样逐包交出去
     */
    public static final class Assembler {
        private final long timeoutMillis;
        private final Map<String, Group> groups = new LinkedHashMap<>();

        private static final class Group {
            final long createdAt = System.currentTimeMillis();
            final TreeMap<Integer, Hj212Packet> parts = new TreeMap<>();
        }

        public Assembler(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        /**
         * @return 收齐了返回合并后的包，还没收齐返回 null
         */
        public synchronized Hj212Packet add(Hj212Packet p) {
            if (!p.isSplit() || p.getPnum() == null || p.getPno() == null || p.version() >= Hj212Packet.VERSION_2025) {
                return p;
            }
            expire();
            String key = p.getMn() + "/" + p.getQn() + "/" + p.getCn();
            Group g = groups.computeIfAbsent(key, k -> new Group());
            g.parts.put(p.getPno(), p);
            if (g.parts.size() < p.getPnum()) {
                return null;
            }
            groups.remove(key);
            Hj212Packet first = g.parts.firstEntry().getValue();
            Hj212Packet merged = new Hj212Packet().setQn(first.getQn()).setSt(first.getSt()).setCn(first.getCn())
                    .setPw(first.getPw()).setMn(first.getMn()).setFlag(first.getFlag() & ~Hj212Packet.FLAG_SPLIT)
                    .setRefill(first.isRefill());
            first.getParams().forEach(merged::putParam);
            for (Hj212Packet part : g.parts.values()) {
                part.getFactors().forEach((code, fields) -> fields.forEach((k, v) -> merged.putFactor(code, k, v)));
            }
            return merged;
        }

        public synchronized int pending() {
            return groups.size();
        }

        private void expire() {
            long now = System.currentTimeMillis();
            groups.values().removeIf(g -> now - g.createdAt > timeoutMillis);
        }
    }
}
