package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * BCD 编解码。
 * <p>
 * BCD 码用 4 位表示一个十进制数字，1 个字节存 2 位数字，比如 {@code 138} 编码后是 {@code 01 38}。
 * JT/T 808 里的终端手机号、时间都是 BCD 码。
 */
public final class BcdUtils {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    /**
     * 808 的时间格式：YY-MM-DD-hh-mm-ss，6 个字节，GMT+8。
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private static final String ZERO_TIME = "000000000000";

    private BcdUtils() {
    }

    /**
     * 写入定长 BCD 码，位数不够时左边补 0。
     * <p>
     * 例如 2013 版手机号固定 6 字节：{@code 13800138000} 写入后为 {@code 01 38 00 13 80 00}。
     *
     * @param length 占用的字节数
     */
    public static void writeBcd(ByteBuf buf, String digits, int length) {
        if (digits.length() > length * 2) {
            throw new IllegalArgumentException("数字位数超过 " + length * 2 + " 位: " + digits);
        }
        int pad = length * 2 - digits.length();
        for (int i = 0; i < length; i++) {
            int hi = digitAt(digits, i * 2 - pad);
            int lo = digitAt(digits, i * 2 + 1 - pad);
            buf.writeByte(hi << 4 | lo);
        }
    }

    /**
     * 读取定长 BCD 码，保留前导 0。
     * <p>
     * 个别设备会用 {@code F} 填充空位，这里不报错，原样输出成 {@code F}，交给上层处理。
     */
    public static String readBcd(ByteBuf buf, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = buf.readUnsignedByte();
            sb.append(DIGITS[b >>> 4]).append(DIGITS[b & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * 读取 6 字节 BCD 时间。
     * <p>
     * 终端还没定位成功时，经常会上报全 0 的时间，这种情况返回 {@code null}。
     *
     * @throws IllegalArgumentException 时间不合法，比如 13 月
     */
    public static LocalDateTime readBcdTime(ByteBuf buf) {
        String s = readBcd(buf, 6);
        if (ZERO_TIME.equals(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, TIME_FORMATTER);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("非法的 BCD 时间: " + s, e);
        }
    }

    /**
     * 写入 6 字节 BCD 时间，{@code null} 写成全 0。
     */
    public static void writeBcdTime(ByteBuf buf, LocalDateTime time) {
        writeBcd(buf, time == null ? ZERO_TIME : time.format(TIME_FORMATTER), 6);
    }

    private static int digitAt(String s, int index) {
        if (index < 0) {
            return 0;
        }
        char c = s.charAt(index);
        if (c < '0' || c > '9') {
            throw new IllegalArgumentException("BCD 只能编码数字: " + s);
        }
        return c - '0';
    }
}
