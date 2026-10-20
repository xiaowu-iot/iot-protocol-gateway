package io.github.xiaowuiot.iot.simulator.web;

import io.github.xiaowuiot.iot.hj212.Hj212Format;
import io.github.xiaowuiot.iot.hj212.Hj212Packet;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 现场机上行报文的模板，以及生成数据的规则。字段和顺序照标准附录 C 的示例。
 * 自动上报、平台提取历史数据、页面上"填入示例"都用这里生成 CP
 */
final class Hj212Templates {

    static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    static final class Entry {
        public final String id;
        public final String cn;
        public final String name;
        public final String group;
        /**
         * 从哪个版本开始有：2017 或 2025（2005 版按 2017 版的命令处理）
         */
        public final int since;
        /**
         * 不为空时用这个 ST（比如噪声 23、炉膛温度 31）
         */
        public final String st;
        /**
         * 默认要不要平台应答
         */
        public final boolean needAck;
        public final String note;

        Entry(String id, String cn, String name, String group, int since, String st, boolean needAck, String note) {
            this.id = id;
            this.cn = cn;
            this.name = name;
            this.group = group;
            this.since = since;
            this.st = st;
            this.needAck = needAck;
            this.note = note;
        }
    }

    static final List<Entry> CATALOG;

    static {
        List<Entry> c = new ArrayList<>();
        c.add(new Entry("2011", "2011", "实时数据", "数据上传", 2017, null, true, "表 C.18；平台回 9014"));
        c.add(new Entry("2051", "2051", "分钟数据", "数据上传", 2017, null, true, "表 C.20；DataTime 是这个时段的开始"));
        c.add(new Entry("2061", "2061", "小时数据", "数据上传", 2017, null, true, "表 C.21"));
        c.add(new Entry("2031", "2031", "日数据", "数据上传", 2017, null, true, "表 C.22"));
        c.add(new Entry("2041", "2041", "设备运行时间日数据", "数据上传", 2017, null, true, "表 C.23；SBn-RT 单位小时"));
        c.add(new Entry("2021", "2021", "设备运行状态", "数据上传", 2017, null, true, "表 C.19；RS：0 停运 1 运行 2 校准 3 维护 4 报警 5 反吹"));
        c.add(new Entry("2081", "2081", "数采仪开机时间", "数据上传", 2017, null, true, "表 C.30"));
        c.add(new Entry("1013", "1013", "现场机时间校准请求", "通知", 2017, null, true, "表 C.6；平台回 9013 后下发 1012"));
        c.add(new Entry("3020-log", "3020", "上传现场机信息（日志）", "现场机信息", 2017, null, true, "表 C.47；日志写在 // 之间，可以有中文（UTF-8）"));
        c.add(new Entry("3020-state", "3020", "上传现场机信息（状态）", "现场机信息", 2017, null, true, "表 C.49"));
        c.add(new Entry("3020-param", "3020", "上传现场机信息（参数）", "现场机信息", 2017, null, true, "表 C.51"));
        c.add(new Entry("9015", "9015", "心跳包", "2025 新增", 2025, null, true, "表 C.54；平台回 9013"));
        c.add(new Entry("1001", "1001", "上传数采仪硬件序号", "2025 新增", 2025, null, true, "表 C.2；没激活时 MN 为空，平台回 9013 后用 1002 分配 MN"));
        c.add(new Entry("1014", "1014", "现场机获取新密钥", "2025 新增", 2025, null, true, "表 C.7；要加密，平台回 9013 后用 1014 下发 NewSK"));
        c.add(new Entry("2013", "2013", "原始监测数据（周期性监测）", "2025 新增", 2025, null, true, "表 C.28"));
        c.add(new Entry("2052", "2052", "噪声单次测量数据", "2025 新增", 2025, "23", true, "表 C.34；ST=23 声环境"));
        c.add(new Entry("2062", "2062", "自动标样核查（校准）数据", "2025 新增", 2025, null, true, "表 C.59；ResultType 1 合格"));
        c.add(new Entry("2111", "2111", "炉膛温度 5 分钟均值", "2025 新增", 2025, "31", true, "表 C.31；垃圾焚烧"));
        c.add(new Entry("3015", "3015", "上传留样信息", "2025 新增", 2025, null, true, "表 C.63"));
        c.add(new Entry("3019", "3019", "上传设备唯一标识", "2025 新增", 2025, null, true, "表 C.46"));
        c.add(new Entry("custom", "", "自定义", "其他", 2005, null, false, "命令编码和 CP 都自己填"));
        CATALOG = Collections.unmodifiableList(c);
    }

    private Hj212Templates() {
    }

    static Entry find(String id) {
        for (Entry e : CATALOG) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 生成模板的 CP 内容（不含两头的 &&）
     */
    static String cp(String id, ToolConfig cfg, LocalDateTime now, LocalDateTime bootTime, String flag, String keyCreateTime) {
        Entry e = find(id);
        if (e == null || e.cn.isEmpty()) {
            return "";
        }
        Hj212Packet p = new Hj212Packet().setQn("1").setCn(e.cn).setMn("M");
        Random r = new Random();
        switch (id) {
            case "2051":
                fill(p, e.cn, cfg, periodStart(e.cn, cfg, now), flag, r);
                break;
            case "2061":
            case "2031":
            case "2041":
                fill(p, e.cn, cfg, periodStart(e.cn, cfg, now), flag, r);
                break;
            case "2081":
                p.putParam("DataTime", now.format(DATA_TIME)).putParam("RestartTime", bootTime.format(DATA_TIME));
                break;
            case "1013":
                p.putParam("PolId", pollutant(cfg));
                break;
            case "3020-log":
                p.putParam("DataTime", now.format(DATA_TIME)).putParam("PolId", pollutant(cfg))
                        .putFactor("i11001", "Info", "//清洗管路//");
                break;
            case "3020-state":
                p.putParam("DataTime", now.format(DATA_TIME)).putParam("PolId", pollutant(cfg))
                        .putFactor("i12001", "Info", "1").putFactor("i12002", "Info", "0");
                break;
            case "3020-param":
                p.putParam("DataTime", now.format(DATA_TIME)).putParam("PolId", pollutant(cfg))
                        .putFactor("i13004", "Info", "168.0").putFactor("i13006", "Info", "40");
                break;
            case "9015":
                break;
            case "1001":
                hardware(p, cfg);
                break;
            case "1014":
                p.putParam("SKCreateTime", keyCreateTime);
                break;
            case "3015":
                p.putParam("DataTime", now.format(DATA_TIME)).putParam("VaseNo", "3")
                        .putFactor("i43001", "Info", "0").putFactor("i43002", "Info", "500")
                        .putFactor("i43003", "Info", "1").putFactor("i43004", "Info", "123456")
                        .putFactor("i43005", "Info", "1").putFactor("i43006", "Info", "5")
                        .putFactor("i43007", "Info", now.withSecond(0).format(DATA_TIME) + "00");
                break;
            case "3019":
                p.putParam("DataTime", now.format(DATA_TIME)).putFactor(pollutant(cfg), "SN", cfg.hardwareSn);
                break;
            default:
                fill(p, e.cn, cfg, now, flag, r);
        }
        return cpOf(p);
    }

    /**
     * 数据类命令的 CP：DataTime + 每个因子的字段。平台提取历史数据时也用它，Random 用时间做种子，同一时刻提取几次数值一样
     */
    static void fill(Hj212Packet p, String cn, ToolConfig cfg, LocalDateTime t, String flag, Random r) {
        p.putParam("DataTime", t.format(DATA_TIME));
        switch (cn) {
            case "2011":
                for (ToolConfig.Factor f : cfg.factors) {
                    if (isStatus(f.code)) {
                        p.putFactor(f.code, "Rtd", "N");
                    } else if (isNoise(f.code)) {
                        p.putFactor(f.code, "Rtd", value(f, r));
                    } else {
                        p.putFactor(f.code, "Rtd", value(f, r)).putFactor(f.code, "Flag", flag);
                    }
                }
                break;
            case "2051":
            case "2061":
            case "2031":
                for (ToolConfig.Factor f : cfg.factors) {
                    if (isStatus(f.code)) {
                        continue;
                    }
                    if (isNoise(f.code)) {
                        p.putFactor(f.code, "Data", value(f, r));
                        continue;
                    }
                    double avg = Double.parseDouble(value(f, r));
                    double spread = Math.abs(avg) * 0.06;
                    if (!"2051".equals(cn) && isFlow(f.code)) {
                        // 小时、日数据的流量带累计值 Cou（表 C.21、C.22），单位按 L/s、m³/s 换算到时段累计
                        double seconds = "2061".equals(cn) ? 3600 : 86400;
                        p.putFactor(f.code, "Cou", fmt(avg * seconds / ("w00000".equals(f.code) ? 1000 : 1), 1));
                    }
                    p.putFactor(f.code, "Min", fmt(Math.max(0, avg - spread), f.decimals))
                            .putFactor(f.code, "Avg", fmt(avg, f.decimals))
                            .putFactor(f.code, "Max", fmt(avg + spread, f.decimals))
                            .putFactor(f.code, "Flag", flag);
                }
                break;
            case "2041":
                p.putFactor("SB1", "RT", fmt(23 + r.nextDouble(), 1)).putFactor("SB2", "RT", fmt(20 + r.nextDouble() * 4, 1));
                break;
            case "2021":
                String rs = "C".equals(flag) ? "2" : "M".equals(flag) ? "5" : "1";
                p.putFactor("SB1", "RS", rs).putFactor("SB2", "RS", "1");
                break;
            case "2013":
                for (ToolConfig.Factor f : cfg.factors) {
                    if (isFlow(f.code) || isNoise(f.code) || isStatus(f.code)) {
                        continue;
                    }
                    p.putFactor(f.code, "SampleTime", t.plusMinutes(1).format(DATA_TIME))
                            .putFactor(f.code, "CompleteTime", t.plusMinutes(42).format(DATA_TIME))
                            .putFactor(f.code, "SampleType", "1")
                            .putFactor(f.code, "Avg", value(f, r))
                            .putFactor(f.code, "Flag", flag);
                }
                break;
            case "2052":
                String[] levels = {"L5", "L10", "L50", "L90", "L95", "Leq", "LMx", "LMn"};
                double[] base = {53, 52, 49, 45, 43, 50, 56, 42};
                for (int i = 0; i < levels.length; i++) {
                    p.putFactor(levels[i], "Data", fmt(base[i] + r.nextGaussian(), 1));
                }
                break;
            case "2062": {
                ToolConfig.Factor f = pollutantFactor(cfg);
                if (f != null) {
                    double std = Math.max(1, Math.round(f.base));
                    p.putFactor(f.code, "SampleRd", fmt(std * (1 + r.nextGaussian() * 0.03), f.decimals))
                            .putFactor(f.code, "StandardValue", fmt(std, 0))
                            .putFactor(f.code, "ResultType", "1");
                }
                break;
            }
            case "2111":
                p.putFactor("a01901", "Avg", fmt(860 + r.nextDouble() * 40, 0));
                break;
            default:
                break;
        }
    }

    /**
     * 自动上报的时段开始时间：分钟数据是上一个 MinInterval 时段，小时数据上一个小时，日数据前一天
     */
    static LocalDateTime periodStart(String cn, ToolConfig cfg, LocalDateTime now) {
        LocalDateTime m = now.withSecond(0).withNano(0);
        switch (cn) {
            case "2051": {
                int step = Math.max(1, cfg.minInterval);
                LocalDateTime floor = m.withMinute(m.getMinute() / step * step);
                return floor.minusMinutes(step);
            }
            case "2061":
                return m.withMinute(0).minusHours(1);
            case "2031":
            case "2041":
                return m.toLocalDate().atStartOfDay().minusDays(1);
            default:
                return now.withNano(0);
        }
    }

    static void hardware(Hj212Packet p, ToolConfig cfg) {
        p.putFactor("i23005", "Info", cfg.hardwareSn).putFactor("i23006", "Info", "A800200A8C6D")
                .putFactor("i23007", "Info", "E86A643C7BBC").putFactor("i23008", "Info", "A763742C1AE1");
    }

    static String pollutant(ToolConfig cfg) {
        ToolConfig.Factor f = pollutantFactor(cfg);
        return f == null ? "w01018" : f.code;
    }

    static ToolConfig.Factor pollutantFactor(ToolConfig cfg) {
        for (ToolConfig.Factor f : cfg.factors) {
            if (!isFlow(f.code) && !isNoise(f.code) && !isStatus(f.code)) {
                return f;
            }
        }
        return null;
    }

    static boolean hasFactor(ToolConfig cfg, String code) {
        for (ToolConfig.Factor f : cfg.factors) {
            if (f.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    static String cpOf(Hj212Packet p) {
        String data = Hj212Format.format(p);
        int start = data.indexOf("CP=&&") + 5;
        return data.substring(start, data.length() - 2);
    }

    static String value(ToolConfig.Factor f, Random r) {
        // pH、水温这类稳定的量波动小一些
        double spread = "w01001".equals(f.code) || "w01010".equals(f.code) || isNoise(f.code) ? 0.02 : 0.08;
        return fmt(Math.max(0, f.base * (1 + r.nextGaussian() * spread)), f.decimals);
    }

    static boolean isFlow(String code) {
        return "w00000".equals(code) || "a00000".equals(code);
    }

    /**
     * 噪声参数：LA、Leq、L10……
     */
    static boolean isNoise(String code) {
        return code.length() >= 2 && code.charAt(0) == 'L' && code.length() <= 4;
    }

    /**
     * 关键生产工况（p 开头），值是 N、St、Sd 这类状态
     */
    static boolean isStatus(String code) {
        return code.startsWith("p");
    }

    static String fmt(double v, int scale) {
        return String.format(Locale.ROOT, "%." + Math.max(0, scale) + "f", v);
    }
}
