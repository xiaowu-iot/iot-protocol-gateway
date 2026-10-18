package io.github.xiaowuiot.iot.hj212;

import io.github.xiaowuiot.iot.codec.util.Checksums;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 数据段的解析和生成，以及整帧的组装。和 Netty 无关，可以单独使用。
 * <pre>
 * ##  0101  QN=...;ST=32;CN=1062;...;CP=&amp;&amp;...&amp;&amp;  1C80  \r\n
 * 包头 长度  数据段                              CRC   包尾
 * </pre>
 * 长度是数据段的字节数，4 位十进制；CRC 只算数据段，4 位十六进制。
 */
public final class Hj212Format {

    private static final String CP_START = "CP=&&";
    private static final String CP_END = "&&";

    private Hj212Format() {
    }

    /**
     * 解析数据段。
     *
     * @throws IllegalArgumentException 格式不对
     */
    public static Hj212Packet parse(String data) {
        int cpIndex = data.indexOf(CP_START);
        if (cpIndex < 0 || !data.endsWith(CP_END)) {
            throw new IllegalArgumentException("找不到 CP=&&...&&");
        }
        Hj212Packet p = new Hj212Packet();
        // CP 之前的部分：key=value，分号分隔
        for (String kv : data.substring(0, cpIndex).split(";")) {
            if (kv.isEmpty()) {
                continue;
            }
            int eq = kv.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("非法字段：" + kv);
            }
            String key = kv.substring(0, eq);
            String value = kv.substring(eq + 1);
            switch (key) {
                case "QN":
                    p.setQn(value);
                    break;
                case "ST":
                    p.setSt(value);
                    break;
                case "CN":
                    p.setCn(value);
                    break;
                case "PW":
                    p.setPw(value);
                    break;
                case "MN":
                    p.setMn(value);
                    break;
                case "Flag":
                    p.setFlag(Integer.parseInt(value));
                    break;
                case "PNUM":
                    p.setPnum(Integer.parseInt(value));
                    break;
                case "PNO":
                    p.setPno(Integer.parseInt(value));
                    break;
                default:
                    // 标准之外的字段不报错，厂家经常加
                    break;
            }
        }
        if (p.getCn() == null || p.getMn() == null) {
            throw new IllegalArgumentException("缺少 CN 或 MN");
        }
        parseCp(data.substring(cpIndex + CP_START.length(), data.length() - CP_END.length()), p);
        return p;
    }

    /**
     * CP 内部：组之间用分号，组内用逗号，每一项都是 key=value。
     * 带 "-" 的是监测因子，比如 w01018-Rtd；不带的是普通参数，比如 DataTime。
     */
    private static void parseCp(String cp, Hj212Packet p) {
        for (String group : cp.split(";")) {
            for (String kv : group.split(",")) {
                if (kv.isEmpty()) {
                    continue;
                }
                int eq = kv.indexOf('=');
                String key = eq < 0 ? kv : kv.substring(0, eq);
                String value = eq < 0 ? "" : kv.substring(eq + 1);
                int dash = key.indexOf('-');
                if (dash > 0) {
                    p.putFactor(key.substring(0, dash), key.substring(dash + 1), value);
                } else {
                    p.putParam(key, value);
                }
            }
        }
    }

    /**
     * 生成数据段。每个监测因子的字段放在一组里（逗号分隔），组之间用分号。
     */
    public static String format(Hj212Packet p) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, "QN", p.getQn());
        append(sb, "ST", p.getSt());
        append(sb, "CN", p.getCn());
        append(sb, "PW", p.getPw());
        append(sb, "MN", p.getMn());
        append(sb, "Flag", String.valueOf(p.getFlag()));
        if (p.getPnum() != null) {
            append(sb, "PNUM", String.valueOf(p.getPnum()));
            append(sb, "PNO", String.valueOf(p.getPno()));
        }
        sb.append(CP_START);
        boolean firstGroup = true;
        for (Map.Entry<String, String> e : p.getParams().entrySet()) {
            if (!firstGroup) {
                sb.append(';');
            }
            firstGroup = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        for (Map.Entry<String, Map<String, String>> f : p.getFactors().entrySet()) {
            if (!firstGroup) {
                sb.append(';');
            }
            firstGroup = false;
            boolean firstField = true;
            for (Map.Entry<String, String> field : f.getValue().entrySet()) {
                if (!firstField) {
                    sb.append(',');
                }
                firstField = false;
                sb.append(f.getKey()).append('-').append(field.getKey()).append('=').append(field.getValue());
            }
        }
        return sb.append(CP_END).toString();
    }

    /**
     * 数据段 → 完整的一帧
     */
    public static String frame(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 9999) {
            throw new IllegalArgumentException("数据段超过 9999 字节，需要分包");
        }
        return String.format("##%04d%s%04X\r\n", bytes.length, data, Checksums.crc16Hj212(bytes));
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(key).append('=').append(value).append(';');
        }
    }
}
