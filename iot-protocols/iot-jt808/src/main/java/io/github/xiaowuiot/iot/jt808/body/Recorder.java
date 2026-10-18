package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 行驶记录仪数据：808 只是"信封"，里面装的是 GB/T 19056（汽车行驶记录仪）的完整数据包。
 * <pre>
 * 0x8700 行驶记录仪数据采集命令：命令字 BYTE | 数据块（GB/T 19056 下行数据包，可以为空）
 * 0x0700 行驶记录仪数据上传：    应答流水号 WORD | 命令字 BYTE | 数据块（GB/T 19056 上行数据包）
 *
 * GB/T 19056 数据包：起始字头 WORD（下行 AA 75，上行 55 7A） | 命令字 BYTE | 数据块长度 WORD | 备用字 BYTE |
 *                   数据块 | 校验字 BYTE（前面所有字节异或）
 * </pre>
 * 协议里套协议：808 的校验管 808 的帧，19056 的校验管记录仪的数据。终端一般只是把记录仪返回的数据原样装进来，
 * 所以平台要按 19056 再解一层。这里实现了几个常用的采集命令，其他命令保留原始字节。
 */
public final class Recorder {

    public static final int CMD_STANDARD_VERSION = 0x00;
    public static final int CMD_DRIVER = 0x01;
    public static final int CMD_TIME = 0x02;
    public static final int CMD_MILEAGE = 0x03;
    public static final int CMD_VEHICLE = 0x05;

    private Recorder() {
    }

    /**
     * 0x8700
     */
    public static final class Command implements Jt808Body {
        public final int cmd;
        public final byte[] block;

        public Command(int cmd, byte[] block) {
            this.cmd = cmd;
            this.block = block;
        }

        public static Command decode(ByteBuf b, Jt808Header h) {
            int cmd = b.readUnsignedByte();
            return new Command(cmd, ByteBufUtil.getBytes(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(cmd);
            out.writeBytes(block);
        }

        @Override
        public String toString() {
            return "RecorderCommand{cmd=0x" + Integer.toHexString(cmd) + ", block=" + block.length + "B}";
        }
    }

    /**
     * 0x0700
     */
    public static final class Upload implements Jt808Body {
        public final int replySerialNo;
        public final int cmd;
        public final byte[] block;

        public Upload(int replySerialNo, int cmd, byte[] block) {
            this.replySerialNo = replySerialNo;
            this.cmd = cmd;
            this.block = block;
        }

        public static Upload decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            int cmd = b.readUnsignedByte();
            return new Upload(serial, cmd, ByteBufUtil.getBytes(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo);
            out.writeByte(cmd);
            out.writeBytes(block);
        }

        public int getReplySerialNo() {
            return replySerialNo;
        }

        @Override
        public String toString() {
            return "RecorderUpload{reply=" + replySerialNo + ", cmd=0x" + Integer.toHexString(cmd) + ", block=" + block.length + "B}";
        }
    }

    // ==================== GB/T 19056 数据包 ====================

    public static final int HEAD_DOWN = 0xAA75;
    public static final int HEAD_UP = 0x557A;

    /**
     * 组一个 GB/T 19056 数据包
     *
     * @param up true 表示记录仪上行（55 7A），false 表示下行（AA 75）
     */
    public static byte[] frame(boolean up, int cmd, byte[] data) {
        ByteBuf b = Unpooled.buffer(7 + data.length);
        b.writeShort(up ? HEAD_UP : HEAD_DOWN);
        b.writeByte(cmd);
        b.writeShort(data.length);
        b.writeByte(0);
        b.writeBytes(data);
        int x = 0;
        for (int i = 0; i < b.writerIndex(); i++) {
            x ^= b.getByte(i);
        }
        b.writeByte(x);
        byte[] r = ByteBufUtil.getBytes(b);
        b.release();
        return r;
    }

    /**
     * 解开 GB/T 19056 上行数据包，校验起始字头、长度和校验字，返回数据块
     */
    public static byte[] unframe(byte[] packet, int expectCmd) {
        ByteBuf b = Unpooled.wrappedBuffer(packet);
        if (b.readableBytes() < 7 || b.readUnsignedShort() != HEAD_UP) {
            throw new IllegalArgumentException("不是记录仪上行数据包");
        }
        int cmd = b.readUnsignedByte();
        int len = b.readUnsignedShort();
        b.skipBytes(1);
        if (cmd != expectCmd || b.readableBytes() != len + 1) {
            throw new IllegalArgumentException("命令字或长度不对：命令字 0x" + Integer.toHexString(cmd) + "，长度 " + len);
        }
        int x = 0;
        for (int i = 0; i < packet.length - 1; i++) {
            x ^= packet[i];
        }
        if ((byte) x != packet[packet.length - 1]) {
            throw new IllegalArgumentException("记录仪数据校验错误");
        }
        byte[] data = new byte[len];
        b.readBytes(data);
        return data;
    }

    /**
     * 按命令字解析数据块。不认识的命令返回 null，调用方保留原始字节
     */
    public static Map<String, Object> parse(int cmd, byte[] data) {
        ByteBuf b = Unpooled.wrappedBuffer(data);
        Map<String, Object> m = new LinkedHashMap<>();
        switch (cmd) {
            case CMD_STANDARD_VERSION:
                m.put("standardYear", BcdUtils.readBcd(b, 1));
                m.put("revision", b.readUnsignedByte());
                return m;
            case CMD_DRIVER:
                m.put("driverLicense", ByteBufUtils.readString(b, 18, StandardCharsets.US_ASCII));
                return m;
            case CMD_TIME:
                m.put("time", BcdUtils.readBcdTime(b).toString());
                return m;
            case CMD_MILEAGE:
                m.put("time", BcdUtils.readBcdTime(b).toString());
                m.put("installTime", BcdUtils.readBcdTime(b).toString());
                m.put("initialKm", Long.parseLong(BcdUtils.readBcd(b, 4)) / 10.0);
                m.put("totalKm", Long.parseLong(BcdUtils.readBcd(b, 4)) / 10.0);
                return m;
            case CMD_VEHICLE:
                m.put("vin", ByteBufUtils.readString(b, 17, StandardCharsets.US_ASCII));
                m.put("plate", ByteBufUtils.readString(b, 12, GBK));
                m.put("plateClass", ByteBufUtils.readString(b, 12, GBK));
                return m;
            default:
                return null;
        }
    }

    /**
     * 模拟器用：生成几个采集命令的数据块
     */
    public static byte[] sampleData(int cmd, LocalDateTime now, double totalKm, String plate) {
        ByteBuf b = Unpooled.buffer();
        switch (cmd) {
            case CMD_STANDARD_VERSION:
                BcdUtils.writeBcd(b, "12", 1);
                b.writeByte(0);
                break;
            case CMD_DRIVER:
                ByteBufUtils.writeString(b, "440300199001011234", 18, StandardCharsets.US_ASCII);
                break;
            case CMD_TIME:
                BcdUtils.writeBcdTime(b, now);
                break;
            case CMD_MILEAGE:
                BcdUtils.writeBcdTime(b, now);
                BcdUtils.writeBcdTime(b, LocalDateTime.of(2025, 3, 1, 9, 0, 0));
                BcdUtils.writeBcd(b, "00000000", 4);
                BcdUtils.writeBcd(b, String.format("%08d", Math.round(totalKm * 10)), 4);
                break;
            case CMD_VEHICLE:
                ByteBufUtils.writeString(b, "LSVAU2180N2123456", 17, StandardCharsets.US_ASCII);
                ByteBufUtils.writeString(b, plate, 12, GBK);
                ByteBufUtils.writeString(b, "重型货车", 12, GBK);
                break;
            default:
                b.release();
                return null;
        }
        byte[] r = ByteBufUtil.getBytes(b);
        b.release();
        return r;
    }
}
