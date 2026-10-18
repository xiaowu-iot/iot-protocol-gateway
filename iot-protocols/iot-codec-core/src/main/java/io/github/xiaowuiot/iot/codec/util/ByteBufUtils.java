package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

/**
 * ByteBuf 读写辅助方法。
 */
public final class ByteBufUtils {

    /**
     * 国标协议里的中文字符串基本都是 GBK 编码。
     */
    public static final Charset GBK = Charset.forName("GBK");

    private ByteBufUtils() {
    }

    /**
     * 在 [fromIndex, toIndex) 范围内查找字节序列，找不到返回 -1。
     * <p>
     * Netty 自带的 {@link ByteBuf#indexOf(int, int, byte)} 只能找单个字节，
     * 1078 的帧头 {@code 30 31 63 64}、212 的 {@code ##} 都是多字节的，所以自己写一个。
     */
    public static int indexOf(ByteBuf buf, byte[] pattern, int fromIndex, int toIndex) {
        int last = toIndex - pattern.length;
        for (int i = fromIndex; i <= last; i++) {
            if (matches(buf, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断从 index 开始的字节是否与 pattern 完全一致，不移动 readerIndex。
     */
    public static boolean matches(ByteBuf buf, int index, byte[] pattern) {
        if (index < 0 || index + pattern.length > buf.writerIndex()) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (buf.getByte(index + i) != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取定长字符串，去掉末尾填充的 0x00。
     */
    public static String readString(ByteBuf buf, int length, Charset charset) {
        int end = buf.readerIndex() + length;
        int realEnd = end;
        while (realEnd > buf.readerIndex() && buf.getByte(realEnd - 1) == 0) {
            realEnd--;
        }
        String s = buf.toString(buf.readerIndex(), realEnd - buf.readerIndex(), charset);
        buf.readerIndex(end);
        return s;
    }

    /**
     * 写入定长字符串，不足的部分补 0x00。
     *
     * @throws IllegalArgumentException 编码后超过指定长度。这里不做截断，
     *                                  因为 GBK 截断可能把一个汉字切成两半，悄悄截断比报错更难排查
     */
    public static void writeString(ByteBuf buf, String s, int length, Charset charset) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(charset);
        if (bytes.length > length) {
            throw new IllegalArgumentException("字符串编码后 " + bytes.length + " 字节，超过限制 " + length + " 字节: " + s);
        }
        buf.writeBytes(bytes);
        buf.writeZero(length - bytes.length);
    }

    /**
     * 读取"1 字节长度 + 内容"格式的字符串，比如 808-2019 注册应答里的鉴权码。
     */
    public static String readByteLengthString(ByteBuf buf, Charset charset) {
        int length = buf.readUnsignedByte();
        return readString(buf, length, charset);
    }

    public static void writeByteLengthString(ByteBuf buf, String s, Charset charset) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(charset);
        if (bytes.length > 0xFF) {
            throw new IllegalArgumentException("字符串编码后超过 255 字节: " + s);
        }
        buf.writeByte(bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * 读取剩余所有字节作为字符串，比如 808 的文本信息下发。
     */
    public static String readRemainingString(ByteBuf buf, Charset charset) {
        return readString(buf, buf.readableBytes(), charset);
    }
}
