package io.github.xiaowuiot.iot.hj212;

import io.github.xiaowuiot.iot.codec.util.Checksums;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据段的解析和生成，以及整帧的组装。和 Netty 无关，可以单独使用。
 * <pre>
 * ##  0101  QN=...;ST=32;CN=1062;...;CP=&amp;&amp;...&amp;&amp;  1C80  \r\n
 * 包头 长度  数据段                              CRC   包尾
 * </pre>
 * 长度是数据段的字节数，4 位十进制；CRC 只算数据段，4 位十六进制。
 * <p>
 * 报文本身是 ASCII，2025 版明确汉字用 UTF-8（现场机日志可以写中文），所以这里统一按 UTF-8 编解码，长度按字节算。
 * 2025 版加密传输时 CP 的内容是密文，加解密见 {@link Hj212Crypto}，这里只处理明文。
 */
public final class Hj212Format {

    static final String CP_START = "CP=&&";
    static final String CP_END = "&&";

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
            String key = kv.substring(0, eq).trim();
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
                case "RF":
                    p.setRefill("1".equals(value));
                    break;
                default:
                    // 标准之外的字段不报错，厂家经常加
                    break;
            }
        }
        // 2025 版没激活的数采仪上传硬件序号（1001）时 MN 是空的："MN=;"，这里得到空字符串，不算缺
        if (p.getCn() == null || p.getMn() == null) {
            throw new IllegalArgumentException("缺少 CN 或 MN");
        }
        parseCp(data.substring(cpIndex + CP_START.length(), data.length() - CP_END.length()), p);
        return p;
    }

    /**
     * CP 内部：组之间用分号，组内用逗号，每一项都是 key=value。
     * 带 "-" 的是监测因子，比如 w01018-Rtd；不带的是普通参数，比如 DataTime。
     * <p>
     * 现场机日志写在一对 "//" 中间（比如 i11001-Info=//清洗管路//），里面可能有分号、逗号，整段当成值，不拆开。
     * 2025 版附录 C 的示例本身有几处不统一：BeginTime 和 EndTime 之间有的用分号、有的用逗号，有的字段名前多一个空格。
     * 这里都按能解析处理：分号、逗号都当分隔符，字段名去掉首尾空格。
     */
    private static void parseCp(String cp, Hj212Packet p) {
        for (String kv : splitCp(cp)) {
            if (kv.trim().isEmpty()) {
                continue;
            }
            int eq = kv.indexOf('=');
            String key = (eq < 0 ? kv : kv.substring(0, eq)).trim();
            String value = eq < 0 ? "" : kv.substring(eq + 1);
            int dash = key.indexOf('-');
            if (dash > 0) {
                p.putFactor(key.substring(0, dash), key.substring(dash + 1), value);
            } else {
                p.putParam(key, value);
            }
        }
    }

    /**
     * 按分号、逗号切开 CP，"//" 包起来的日志原样保留。
     * 只有紧跟在 "=" 后面的 "//" 才算日志开头，后面是分号、逗号或结尾的 "//" 才算日志结尾，
     * 这样值里的网址（FwUrl=http://...）不会被当成日志
     */
    static List<String> splitCp(String cp) {
        List<String> items = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inLog = false;
        for (int i = 0; i < cp.length(); i++) {
            char c = cp.charAt(i);
            boolean slashes = c == '/' && i + 1 < cp.length() && cp.charAt(i + 1) == '/';
            if (slashes && !inLog && cur.length() > 0 && cur.charAt(cur.length() - 1) == '=') {
                inLog = true;
                cur.append("//");
                i++;
            } else if (slashes && inLog && (i + 2 == cp.length() || cp.charAt(i + 2) == ';' || cp.charAt(i + 2) == ',')) {
                inLog = false;
                cur.append("//");
                i++;
            } else if (!inLog && (c == ';' || c == ',')) {
                items.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        items.add(cur.toString());
        return items;
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
        if (p.isRefill()) {
            append(sb, "RF", "1");
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
     * 数据段 → 完整的一帧（明文）
     */
    public static String frame(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 9999) {
            throw new IllegalArgumentException("数据段超过 9999 字节，需要分包");
        }
        return String.format("##%04d%s%04X\r\n", bytes.length, data, Checksums.crc16Hj212(bytes));
    }

    /**
     * 数据段 → 完整一帧的字节。key 不为空时按 2025 版加密 CP：长度和 CRC 都按明文算，先组包再加密
     */
    public static byte[] frameBytes(String data, byte[] key) {
        byte[] plain = data.getBytes(StandardCharsets.UTF_8);
        if (plain.length > 9999) {
            throw new IllegalArgumentException("数据段超过 9999 字节，需要分包");
        }
        byte[] body = key == null ? plain : Hj212Crypto.encrypt(plain, key);
        byte[] head = String.format("##%04d", plain.length).getBytes(StandardCharsets.US_ASCII);
        byte[] tail = String.format("%04X\r\n", Checksums.crc16Hj212(plain)).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + body.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        System.arraycopy(tail, 0, out, head.length + body.length, tail.length);
        return out;
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(key).append('=').append(value).append(';');
        }
    }
}
