package io.github.xiaowuiot.iot.slp.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SLP 各命令的数据部分。都很短，放在一个文件里方便对照协议文档看。
 * <p>
 * 每个类都遵守"版本兼容"的规则：解析时只读自己认识的字段，<b>多出来的字节不报错</b>，
 * 这样新版本设备在末尾追加字段，老平台照样能解析。
 */
public final class SlpBodies {

    private SlpBodies() {
    }

    public interface Body {
        void encode(ByteBuf out);
    }

    static String readStr(ByteBuf buf) {
        return ByteBufUtils.readByteLengthString(buf, StandardCharsets.US_ASCII);
    }

    static void writeStr(ByteBuf buf, String s) {
        ByteBufUtils.writeByteLengthString(buf, s, StandardCharsets.US_ASCII);
    }

    /**
     * 只有一个结果字节的应答：0x83、0x90、0xA0、0xA2
     */
    public static final class Result implements Body {
        public static final int SUCCESS = 0;

        private final int code;

        public Result(int code) {
            this.code = code;
        }

        public static Result decode(ByteBuf b) {
            return new Result(b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeByte(code);
        }

        public int getCode() {
            return code;
        }

        public boolean isSuccess() {
            return code == SUCCESS;
        }

        @Override
        public String toString() {
            return "Result{" + code + '}';
        }
    }

    /**
     * 0x01 登录：固件版本 STR | 格口数 BYTE
     */
    public static final class Login implements Body {
        private final String firmwareVersion;
        private final int doorCount;

        public Login(String firmwareVersion, int doorCount) {
            this.firmwareVersion = firmwareVersion;
            this.doorCount = doorCount;
        }

        public static Login decode(ByteBuf b) {
            return new Login(readStr(b), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out) {
            writeStr(out, firmwareVersion);
            out.writeByte(doorCount);
        }

        public String getFirmwareVersion() {
            return firmwareVersion;
        }

        public int getDoorCount() {
            return doorCount;
        }

        @Override
        public String toString() {
            return "Login{firmware=" + firmwareVersion + ", doors=" + doorCount + '}';
        }
    }

    /**
     * 0x81 登录应答：结果 BYTE | 服务器时间 TIME | 心跳间隔 WORD
     */
    public static final class LoginAck implements Body {
        public static final int SUCCESS = 0;
        public static final int NOT_REGISTERED = 1;
        public static final int VERSION_UNSUPPORTED = 2;

        private final int result;
        private final long serverTime;
        private final int heartbeatSeconds;

        public LoginAck(int result, long serverTime, int heartbeatSeconds) {
            this.result = result;
            this.serverTime = serverTime;
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public static LoginAck decode(ByteBuf b) {
            return new LoginAck(b.readUnsignedByte(), b.readUnsignedInt(), b.readUnsignedShort());
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeByte(result);
            out.writeInt((int) serverTime);
            out.writeShort(heartbeatSeconds);
        }

        public int getResult() {
            return result;
        }

        public long getServerTime() {
            return serverTime;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        @Override
        public String toString() {
            return "LoginAck{result=" + result + ", serverTime=" + serverTime + ", heartbeat=" + heartbeatSeconds + '}';
        }
    }

    /**
     * 0x03 格口状态：时间 TIME | 数量 BYTE | [格口号 BYTE | 状态 BYTE] × 数量
     */
    public static final class DoorStatus implements Body {
        public static final int OPEN = 1;
        public static final int OCCUPIED = 1 << 1;
        public static final int FAULT = 1 << 2;

        private final long time;
        private final List<int[]> doors;

        /**
         * @param doors 每个元素是 {格口号, 状态}
         */
        public DoorStatus(long time, List<int[]> doors) {
            this.time = time;
            this.doors = doors;
        }

        public static DoorStatus decode(ByteBuf b) {
            long time = b.readUnsignedInt();
            int n = b.readUnsignedByte();
            List<int[]> doors = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                doors.add(new int[]{b.readUnsignedByte(), b.readUnsignedByte()});
            }
            return new DoorStatus(time, doors);
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeInt((int) time);
            out.writeByte(doors.size());
            for (int[] d : doors) {
                out.writeByte(d[0]).writeByte(d[1]);
            }
        }

        public long getTime() {
            return time;
        }

        public List<int[]> getDoors() {
            return Collections.unmodifiableList(doors);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("DoorStatus{time=" + time);
            for (int[] d : doors) {
                sb.append(", #").append(d[0]).append('=').append(d[1]);
            }
            return sb.append('}').toString();
        }
    }

    /**
     * 0x10 开门：格口号 BYTE | 订单号 STR
     */
    public static final class OpenDoor implements Body {
        private final int door;
        private final String orderNo;

        public OpenDoor(int door, String orderNo) {
            this.door = door;
            this.orderNo = orderNo;
        }

        public static OpenDoor decode(ByteBuf b) {
            return new OpenDoor(b.readUnsignedByte(), readStr(b));
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeByte(door);
            writeStr(out, orderNo);
        }

        public int getDoor() {
            return door;
        }

        public String getOrderNo() {
            return orderNo;
        }

        @Override
        public String toString() {
            return "OpenDoor{door=" + door + ", order=" + orderNo + '}';
        }
    }

    /**
     * 0x20 升级通知：版本 STR | 大小 DWORD | CRC32 DWORD
     */
    public static final class OtaNotify implements Body {
        private final String version;
        private final long size;
        private final long crc32;

        public OtaNotify(String version, long size, long crc32) {
            this.version = version;
            this.size = size;
            this.crc32 = crc32;
        }

        public static OtaNotify decode(ByteBuf b) {
            return new OtaNotify(readStr(b), b.readUnsignedInt(), b.readUnsignedInt());
        }

        @Override
        public void encode(ByteBuf out) {
            writeStr(out, version);
            out.writeInt((int) size);
            out.writeInt((int) crc32);
        }

        public String getVersion() {
            return version;
        }

        public long getSize() {
            return size;
        }

        public long getCrc32() {
            return crc32;
        }

        @Override
        public String toString() {
            return String.format("OtaNotify{version=%s, size=%d, crc32=%08X}", version, size, crc32);
        }
    }

    /**
     * 0x21 请求固件分片：版本 STR | 偏移 DWORD | 长度 WORD
     */
    public static final class OtaChunkRequest implements Body {
        private final String version;
        private final long offset;
        private final int length;

        public OtaChunkRequest(String version, long offset, int length) {
            this.version = version;
            this.offset = offset;
            this.length = length;
        }

        public static OtaChunkRequest decode(ByteBuf b) {
            return new OtaChunkRequest(readStr(b), b.readUnsignedInt(), b.readUnsignedShort());
        }

        @Override
        public void encode(ByteBuf out) {
            writeStr(out, version);
            out.writeInt((int) offset);
            out.writeShort(length);
        }

        public String getVersion() {
            return version;
        }

        public long getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }

        @Override
        public String toString() {
            return "OtaChunkRequest{version=" + version + ", offset=" + offset + ", length=" + length + '}';
        }
    }

    /**
     * 0xA1 固件分片：结果 BYTE | 偏移 DWORD | 数据（剩余全部字节）
     */
    public static final class OtaChunk implements Body {
        public static final int SUCCESS = 0;
        public static final int VERSION_NOT_FOUND = 1;
        public static final int OUT_OF_RANGE = 2;

        private final int result;
        private final long offset;
        private final byte[] data;

        public OtaChunk(int result, long offset, byte[] data) {
            this.result = result;
            this.offset = offset;
            this.data = data;
        }

        public static OtaChunk decode(ByteBuf b) {
            int result = b.readUnsignedByte();
            long offset = b.readUnsignedInt();
            return new OtaChunk(result, offset, ByteBufUtil.getBytes(b));
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeByte(result);
            out.writeInt((int) offset);
            out.writeBytes(data);
        }

        public int getResult() {
            return result;
        }

        public long getOffset() {
            return offset;
        }

        public byte[] getData() {
            return data;
        }

        @Override
        public String toString() {
            return "OtaChunk{result=" + result + ", offset=" + offset + ", size=" + data.length + '}';
        }
    }

    /**
     * 0x22 升级结果：版本 STR | 结果 BYTE
     */
    public static final class OtaResult implements Body {
        public static final int SUCCESS = 0;
        public static final int CRC_MISMATCH = 1;
        public static final int WRITE_FAILED = 2;

        private final String version;
        private final int result;

        public OtaResult(String version, int result) {
            this.version = version;
            this.result = result;
        }

        public static OtaResult decode(ByteBuf b) {
            return new OtaResult(readStr(b), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out) {
            writeStr(out, version);
            out.writeByte(result);
        }

        public String getVersion() {
            return version;
        }

        public int getResult() {
            return result;
        }

        @Override
        public String toString() {
            return "OtaResult{version=" + version + ", result=" + result + '}';
        }
    }
}
