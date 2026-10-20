package io.github.xiaowuiot.iot.hj212;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2005 版、2017 版、2025 版的识别与转换。
 * <p>
 * 很多地方还有大量 2005 版的数采仪在运行，同一个平台上几个版本并存是常态。几版的帧格式、CRC 一样，主要差别：
 * <ul>
 *     <li>版本号：写在 Flag 的 bit2~7，2005 版是 0，2017 版是 1，2025 版是 2。
 *     所以 2017 版的 Flag 是 4~7，2025 版是 8~11（bit0 要不要应答，bit1 是否分包）</li>
 *     <li>MN：2017 版 24 位，2005 版 14 位；2025 版也是 24 位，但改叫"数采仪入网编码"，由上位机按硬件指纹分配（命令 1001 / 1002）</li>
 *     <li>因子编码：2017 版是"字母 + 5 位数字"（w01018），2005 版是两三位的编码（011）。
 *     2025 版沿用 2017 版的编码，另外新增用电（d 开头）、关键生产工况（p 开头）两类参数</li>
 *     <li>2025 版还新增了数据段加密（SM4）、补传标志 RF、心跳包、最长 3 位的数据标记，见 {@link Hj212Crypto}、{@link Hj212Commands}</li>
 * </ul>
 * 平台内部统一按 2017 / 2025 版的编码处理，2005 版的数据进来时先转换，同时保留原始编码，方便排查。
 * 下面的对照表只列了几个常用的，完整对照以两版标准的附录为准。
 */
public final class Hj212Versions {

    public static final String V2005 = "2005";
    public static final String V2017 = "2017";
    public static final String V2025 = "2025";

    private static final Map<String, String> CODES_2005 = new HashMap<>();

    static {
        // 水
        CODES_2005.put("001", "w01001");
        CODES_2005.put("011", "w01018");
        CODES_2005.put("060", "w21003");
        // 气
        CODES_2005.put("01", "a34013");
        CODES_2005.put("02", "a21026");
        CODES_2005.put("03", "a21002");
    }

    private Hj212Versions() {
    }

    /**
     * 判断版本：先看 Flag 里的版本号，再看 MN 长度。都不像 2017 / 2025 版才判为 2005 版
     */
    public static String detect(Hj212Packet p) {
        if (p.version() == Hj212Packet.VERSION_2025) {
            return V2025;
        }
        if (p.version() == Hj212Packet.VERSION_2017) {
            return V2017;
        }
        return p.getMn() != null && p.getMn().length() == 24 ? V2017 : V2005;
    }

    /**
     * 2005 版的因子编码换成 2017 版。对照表里没有的保留原编码
     */
    public static String toCode2017(String code2005) {
        return CODES_2005.getOrDefault(code2005, code2005);
    }

    public static Map<String, String> codeTable() {
        return Collections.unmodifiableMap(CODES_2005);
    }

    /**
     * 把 2005 版的包转成 2017 版的因子编码。返回新包，原包不变
     *
     * @param originalCodes 输出参数：2017 编码 → 2005 原始编码
     */
    public static Hj212Packet normalize(Hj212Packet p, Map<String, String> originalCodes) {
        if (!V2005.equals(detect(p))) {
            return p;
        }
        Hj212Packet n = new Hj212Packet().setQn(p.getQn()).setSt(p.getSt()).setCn(p.getCn()).setPw(p.getPw())
                .setMn(p.getMn()).setFlag(p.getFlag()).setPnum(p.getPnum()).setPno(p.getPno()).setRefill(p.isRefill());
        p.getParams().forEach(n::putParam);
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> f : p.getFactors().entrySet()) {
            String code = toCode2017(f.getKey());
            if (!code.equals(f.getKey())) {
                mapped.put(code, f.getKey());
            }
            f.getValue().forEach((k, v) -> n.putFactor(code, k, v));
        }
        if (originalCodes != null) {
            originalCodes.putAll(mapped);
        }
        if (p.isRequested()) {
            n.markRequested();
        }
        return n;
    }
}
