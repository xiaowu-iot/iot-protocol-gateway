package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;

import java.util.Arrays;

/**
 * 字节转义器。
 * <p>
 * 用分隔符划分帧的协议，内容里一旦出现分隔符就会被误认为帧边界，所以要转义。
 * 以 JT/T 808 为例，转义字符是 {@code 0x7D}：
 * <pre>
 *   0x7E → 0x7D 0x02
 *   0x7D → 0x7D 0x01
 * </pre>
 * 转义字符自己也必须被转义，否则接收方没法区分"真正的 0x7D"和"转义开始"。
 * <p>
 * 构造参数里的转义规则由协议模块提供，这里不写死任何一种协议。
 */
public final class ByteEscaper {

    private final byte escape;

    /**
     * 原始字节 → 转义码，-1 表示不需要转义
     */
    private final int[] escapeTable = new int[256];

    /**
     * 转义码 → 原始字节，-1 表示非法的转义码
     */
    private final int[] unescapeTable = new int[256];

    /**
     * @param escape 转义字符
     * @param raws   需要转义的原始字节
     * @param codes  与 raws 一一对应，写在转义字符后面的字节
     */
    public ByteEscaper(byte escape, byte[] raws, byte[] codes) {
        if (raws.length != codes.length) {
            throw new IllegalArgumentException("raws 和 codes 的长度必须一致");
        }
        Arrays.fill(escapeTable, -1);
        Arrays.fill(unescapeTable, -1);
        boolean escapesItself = false;
        for (int i = 0; i < raws.length; i++) {
            escapeTable[raws[i] & 0xFF] = codes[i] & 0xFF;
            unescapeTable[codes[i] & 0xFF] = raws[i] & 0xFF;
            if (raws[i] == escape) {
                escapesItself = true;
            }
        }
        if (!escapesItself) {
            throw new IllegalArgumentException("转义字符自身也必须在转义规则里");
        }
        this.escape = escape;
    }

    /**
     * 把 src 的可读字节转义后写入 dst，src 会被读完。
     */
    public void escape(ByteBuf src, ByteBuf dst) {
        for (int i = src.readerIndex(); i < src.writerIndex(); i++) {
            byte b = src.getByte(i);
            int code = escapeTable[b & 0xFF];
            if (code < 0) {
                dst.writeByte(b);
            } else {
                dst.writeByte(escape);
                dst.writeByte(code);
            }
        }
        src.readerIndex(src.writerIndex());
    }

    /**
     * 把 src 中 [index, index + length) 的数据还原后写入 dst，不移动 src 的 readerIndex。
     *
     * @return false 表示遇到了非法转义（转义字符后面跟的不是合法转义码，或者转义字符在最后一个字节）
     */
    public boolean unescape(ByteBuf src, int index, int length, ByteBuf dst) {
        int end = index + length;
        for (int i = index; i < end; i++) {
            byte b = src.getByte(i);
            if (b != escape) {
                dst.writeByte(b);
                continue;
            }
            if (++i >= end) {
                return false;
            }
            int raw = unescapeTable[src.getByte(i) & 0xFF];
            if (raw < 0) {
                return false;
            }
            dst.writeByte(raw);
        }
        return true;
    }
}
