package io.github.xiaowuiot.iot.codec.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 常用校验算法。
 * <p>
 * 所有方法都按绝对下标读取，不会移动 ByteBuf 的 readerIndex。
 */
public final class Checksums {

    private Checksums() {
    }

    /**
     * BCC 异或校验：所有字节依次异或，结果占 1 个字节。
     * <p>
     * JT/T 808 的校验码就是它：从消息头开始，到校验码前一个字节为止（注意是<b>转义还原之后</b>的数据）。
     */
    public static int bcc(ByteBuf buf, int index, int length) {
        int result = 0;
        for (int i = index; i < index + length; i++) {
            result ^= buf.getByte(i);
        }
        return result & 0xFF;
    }

    public static int bcc(byte[] bytes) {
        return bcc(Unpooled.wrappedBuffer(bytes), 0, bytes.length);
    }

    /**
     * CRC-16/MODBUS：多项式 0x8005（反转后 0xA001），初值 0xFFFF。
     * <p>
     * 很多厂家私有协议用的都是这个。注意 MODBUS 协议本身在传输时是<b>低字节在前</b>，
     * 但私有协议经常是高字节在前，对接时一定要看清文档或者对着报文验算一下。
     */
    public static int crc16Modbus(ByteBuf buf, int index, int length) {
        int crc = 0xFFFF;
        for (int i = index; i < index + length; i++) {
            crc ^= buf.getByte(i) & 0xFF;
            crc = shift8(crc);
        }
        return crc;
    }

    public static int crc16Modbus(byte[] bytes) {
        return crc16Modbus(Unpooled.wrappedBuffer(bytes), 0, bytes.length);
    }

    /**
     * HJ212-2017 附录中给出的 CRC16 算法。
     * <p>
     * 和 MODBUS 只差一处：每个字节是和 {@code crc >> 8} 异或，而不是和 crc 本身异或。
     * 很多人直接拿 MODBUS 的 CRC 去算 212 报文，结果怎么都对不上，问题就出在这里。
     * <p>
     * 校验范围是数据段（不含 {@code ##} 和 4 位长度），结果以 4 位大写十六进制跟在数据段后面。
     */
    public static int crc16Hj212(ByteBuf buf, int index, int length) {
        int crc = 0xFFFF;
        for (int i = index; i < index + length; i++) {
            crc = (crc >>> 8) ^ (buf.getByte(i) & 0xFF);
            crc = shift8(crc);
        }
        return crc;
    }

    public static int crc16Hj212(byte[] bytes) {
        return crc16Hj212(Unpooled.wrappedBuffer(bytes), 0, bytes.length);
    }

    /**
     * CRC-16/CCITT-FALSE：多项式 0x1021，初始值 0xFFFF，高位在前、不反转。JT/T 809 用的就是它。
     * "123456789" 的结果是 0x29B1
     */
    public static int crc16CcittFalse(ByteBuf buf, int index, int length) {
        int crc = 0xFFFF;
        for (int i = index; i < index + length; i++) {
            crc ^= (buf.getByte(i) & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            crc &= 0xFFFF;
        }
        return crc;
    }

    public static int crc16CcittFalse(byte[] bytes) {
        return crc16CcittFalse(Unpooled.wrappedBuffer(bytes), 0, bytes.length);
    }

    private static int shift8(int crc) {
        for (int j = 0; j < 8; j++) {
            if ((crc & 1) != 0) {
                crc = (crc >>> 1) ^ 0xA001;
            } else {
                crc >>>= 1;
            }
        }
        return crc;
    }
}
