package io.github.xiaowuiot.iot.simulator.web;

import io.github.xiaowuiot.protocol.codec.util.Checksums;
import io.github.xiaowuiot.protocol.hj212.Hj212Commands;
import io.github.xiaowuiot.protocol.hj212.Hj212Crypto;
import io.github.xiaowuiot.protocol.hj212.Hj212Format;
import io.github.xiaowuiot.protocol.hj212.Hj212Packet;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把一帧拆开来看：包头、长度、数据段、CRC，加密的用密钥解开，再解析成字段。
 * 收发的每一帧、"报文解析"页粘贴进来的报文都走这里，错误不抛异常，写在结果里
 */
final class FrameInspector {

    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f\\s]+");

    private FrameInspector() {
    }

    static final class Result {
        byte[] frame;
        String plain;
        boolean crcOk;
        boolean encrypted;
        Hj212Packet packet;
        final Map<String, Object> json = new LinkedHashMap<>();
    }

    /**
     * 页面粘贴的内容 → 帧字节：十六进制、完整的 ##...、或者只有数据段（自动补包头、长度、CRC）
     */
    static byte[] toFrame(String text) {
        String t = text.trim();
        if (!t.startsWith("##") && t.length() % 2 == 0 && t.length() >= 24 && HEX.matcher(t).matches()) {
            byte[] b = hexToBytes(t.replaceAll("\\s", ""));
            if (b.length > 0 && b[0] == '#') {
                return b;
            }
        }
        if (!t.startsWith("##")) {
            return Hj212Format.frameBytes(t, null);
        }
        return (t.endsWith("\r\n") ? t : t + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    static Result inspect(byte[] frame, List<byte[]> keys) {
        Result r = new Result();
        r.frame = frame;
        Map<String, Object> j = r.json;
        j.put("hex", hex(frame));
        j.put("size", frame.length);
        int end = frame.length;
        if (end >= 2 && frame[end - 2] == '\r' && frame[end - 1] == '\n') {
            end -= 2;
        }
        if (frame.length < 12 || frame[0] != '#' || frame[1] != '#' || end - 6 < 4) {
            j.put("text", display(frame));
            j.put("error", "不是 ##0000...CRC 格式的 212 报文");
            return r;
        }
        String lengthText = new String(frame, 2, 4, StandardCharsets.US_ASCII);
        byte[] data = new byte[end - 4 - 6];
        System.arraycopy(frame, 6, data, 0, data.length);
        String crcText = new String(frame, end - 4, 4, StandardCharsets.US_ASCII);
        j.put("lengthField", lengthText);
        j.put("dataLength", data.length);
        try {
            j.put("lengthOk", Integer.parseInt(lengthText) == data.length);
        } catch (NumberFormatException e) {
            j.put("lengthOk", false);
        }
        j.put("crcField", crcText);
        int crc = Checksums.crc16Hj212(data);
        j.put("crcCalc", String.format("%04X", crc));
        byte[] plain = data;
        int expected = -1;
        try {
            expected = Integer.parseInt(crcText, 16);
        } catch (NumberFormatException ignored) {
            // 下面按 CRC 不对处理
        }
        r.crcOk = expected == crc;
        if (!r.crcOk && keys != null && expected >= 0) {
            // CRC 是按明文算的：对不上可能是加密报文，用密钥解开再算
            for (byte[] key : keys) {
                if (key == null) {
                    continue;
                }
                try {
                    byte[] p = Hj212Crypto.decrypt(data, key);
                    if (Checksums.crc16Hj212(p) == expected) {
                        plain = p;
                        r.encrypted = true;
                        r.crcOk = true;
                        j.put("crcCalc", String.format("%04X", expected));
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // 换下一把
                }
            }
        }
        j.put("crcOk", r.crcOk);
        j.put("encrypted", r.encrypted);
        j.put("text", r.encrypted ? display(frame) : utf8OrEscaped(frame));
        r.plain = utf8OrEscaped(plain);
        j.put("plain", r.plain);
        try {
            r.packet = Hj212Format.parse(new String(plain, StandardCharsets.UTF_8));
            if (r.encrypted) {
                r.packet.markEncrypted();
            }
            j.put("packet", describe(r.packet));
        } catch (RuntimeException e) {
            j.put("error", (r.crcOk ? "格式错误：" : "CRC 不对，可能是加密报文但没有密钥：") + e.getMessage());
            Hj212Packet header = header(r.plain);
            if (header != null) {
                j.put("packet", describe(header));
            }
        }
        return r;
    }

    /**
     * 只解析 CP 前面的包头（加密报文没有密钥时 CP 是密文）
     */
    static Hj212Packet header(String data) {
        int cp = data.indexOf("CP=&&");
        if (cp < 0) {
            return null;
        }
        try {
            return Hj212Format.parse(data.substring(0, cp) + "CP=&&&&");
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> describe(Hj212Packet p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qn", p.getQn());
        m.put("st", p.getSt());
        m.put("stName", Hj212Names.st(p.getSt()));
        m.put("cn", p.getCn());
        m.put("cnName", Hj212Names.cn(p.getCn()));
        m.put("pw", p.getPw());
        m.put("mn", p.getMn());
        m.put("flag", p.getFlag());
        m.put("version", Hj212Names.version(p.version()));
        m.put("needAck", p.needAck());
        m.put("split", p.isSplit());
        if (p.getPnum() != null) {
            m.put("pnum", p.getPnum());
            m.put("pno", p.getPno());
        }
        m.put("refill", p.isRefill());
        m.put("params", p.getParams());
        List<Map<String, Object>> factors = new ArrayList<>();
        p.getFactors().forEach((code, fields) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", code);
            f.put("fields", fields);
            String flag = fields.get("Flag");
            if (flag != null) {
                f.put("flagName", Hj212Commands.flagName(flag, p.version()));
            }
            factors.add(f);
        });
        m.put("factors", factors);
        return m;
    }

    /**
     * 明文报文按 UTF-8 显示（日志里可能有中文）；不是合法 UTF-8 的（密文）可见字符原样、其他写成 \xNN
     */
    static String utf8OrEscaped(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return display(b);
        }
    }

    static String display(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length + 16);
        for (byte x : b) {
            int c = x & 0xff;
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c >= 0x20 && c < 0x7f) {
                sb.append((char) c);
            } else {
                sb.append(String.format("\\x%02X", c));
            }
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
