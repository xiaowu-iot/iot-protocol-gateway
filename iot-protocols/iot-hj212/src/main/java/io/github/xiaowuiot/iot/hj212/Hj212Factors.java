package io.github.xiaowuiot.iot.hj212;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 监测因子编码表。
 * <p>
 * 2017 版的因子编码是"1 个小写字母 + 5 位数字"：w 开头是水（water），a 开头是气（air），比如 w01018 化学需氧量、
 * a21026 二氧化硫。完整的编码表在标准附录里，这里只登记了几个常用的（名称和单位以标准原文为准）。
 * <p>
 * 标准里没有的监测项（地方平台要求的、企业自己关心的），编码要<b>和平台方书面约定</b>，并且确认不和标准附录里的编码冲突。
 * 约定好的编码用 {@link #register} 登记；没登记的编码不报错，原样保留，由下游决定怎么处理。
 */
public final class Hj212Factors {

    /**
     * 2017 版因子编码的格式
     */
    public static final Pattern CODE_2017 = Pattern.compile("[a-z]\\d{5}");

    /**
     * 2025 版的 6 位编码：第 1 位类别字母，后 5 位数字或小写字母（新增的用电参数 d 开头、关键生产工况 p 开头，
     * 像 p11a01 这种第 4 位是字母）
     */
    public static final Pattern CODE_2025 = Pattern.compile("[a-z][0-9a-z]{5}");

    /**
     * 噪声参数：LA、Leq、L5、L10、Ldn……，不是 6 位编码
     */
    public static final Pattern NOISE = Pattern.compile("L[0-9A-Za-z]{1,3}");

    public static final class Factor {
        public final String code;
        public final String name;
        public final String unit;
        public final int decimals;
        public final boolean custom;

        public Factor(String code, String name, String unit, int decimals, boolean custom) {
            this.code = code;
            this.name = name;
            this.unit = unit;
            this.decimals = decimals;
            this.custom = custom;
        }

        @Override
        public String toString() {
            return code + " " + name + (unit.isEmpty() ? "" : "（" + unit + "）") + (custom ? " [自定义]" : "");
        }
    }

    private static final Map<String, Factor> FACTORS = new ConcurrentHashMap<>();

    static {
        standard("w01018", "化学需氧量", "mg/L", 1);
        standard("w21003", "氨氮", "mg/L", 2);
        standard("w01001", "pH 值", "", 2);
        standard("w21011", "总磷", "mg/L", 3);
        standard("w21001", "总氮", "mg/L", 2);
        standard("a21026", "二氧化硫", "mg/m³", 1);
        standard("a21002", "氮氧化物", "mg/m³", 1);
        standard("a34013", "颗粒物", "mg/m³", 1);
        standard("a19001", "氧气含量", "%", 1);
    }

    private Hj212Factors() {
    }

    private static void standard(String code, String name, String unit, int decimals) {
        FACTORS.put(code, new Factor(code, name, unit, decimals, false));
    }

    /**
     * 登记一个和平台方约定好的自定义因子。和标准编码冲突时拒绝：自定义编码不能改变标准编码的含义
     */
    public static void register(String code, String name, String unit, int decimals) {
        Factor existing = FACTORS.get(code);
        if (existing != null && !existing.custom) {
            throw new IllegalArgumentException("自定义因子 " + code + " 和标准编码冲突：" + existing);
        }
        FACTORS.put(code, new Factor(code, name, unit, decimals, true));
    }

    public static void unregister(String code) {
        Factor f = FACTORS.get(code);
        if (f != null && f.custom) {
            FACTORS.remove(code);
        }
    }

    /**
     * @return 没登记的返回 null
     */
    public static Factor get(String code) {
        return FACTORS.get(code);
    }

    /**
     * 是不是监测因子编码（而不是 i1xxxx 现场机信息编码之类）
     */
    public static boolean isFactorCode(String code) {
        if (code == null || code.isEmpty() || code.charAt(0) == 'i') {
            return false;
        }
        return CODE_2017.matcher(code).matches() || CODE_2025.matcher(code).matches() || NOISE.matcher(code).matches();
    }
}
