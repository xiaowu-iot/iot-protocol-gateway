package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;

/**
 * 十六进制工具。
 * <p>
 * 调协议基本就是对着十六进制报文看，所以收发日志、单元测试里的报文样例都统一用这里的方法转换。
 * 解析时允许报文里带空格、换行，方便直接从抓包工具或协议文档里复制过来。
 */
public final class HexUtils {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private HexUtils() {
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = DIGITS[v >>> 4];
            out[i * 2 + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 输出可读区域的十六进制，不移动 readerIndex，日志里打印报文用。
     */
    public static String toHex(ByteBuf buf) {
        if (buf == null) {
            return "";
        }
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return toHex(bytes);
    }

    /**
     * 十六进制字符串转字节数组，忽略其中的空白字符，大小写均可。
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String s = hex.replaceAll("\\s+", "");
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须是偶数: " + hex);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法的十六进制字符: " + s.substring(i * 2, i * 2 + 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
