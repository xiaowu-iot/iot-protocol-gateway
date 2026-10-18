package io.github.xiaowuiot.iot.jt808.body;

import io.netty.buffer.ByteBuf;

/**
 * 和电子围栏相关的两个报警附加信息。0x12（进出区域 / 路线）见 {@link AreaAlarmInfo}。
 */
public final class AlarmExtras {

    private AlarmExtras() {
    }

    /**
     * 0x11 超速报警附加信息：在哪个区域或路段超的速。
     * <pre>位置类型 BYTE（0 无特定位置 1 圆形 2 矩形 3 多边形 4 路段） | 区域或路段 ID DWORD（位置类型为 0 时没有）</pre>
     * 长度是 1 或 5，解析时要看位置类型。
     */
    public static final class Overspeed implements Location.Extra {
        public static final int ID = 0x11;
        public static final int NONE = 0;
        public static final int SEGMENT = 4;

        public final int positionType;
        public final long areaId;

        public Overspeed(int positionType, long areaId) {
            this.positionType = positionType;
            this.areaId = areaId;
        }

        public static Overspeed decode(ByteBuf v) {
            int type = v.readUnsignedByte();
            return new Overspeed(type, type == NONE ? 0 : v.readUnsignedInt());
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeByte(positionType);
            if (positionType != NONE) {
                out.writeInt((int) areaId);
            }
        }

        public int getPositionType() {
            return positionType;
        }

        public long getAreaId() {
            return areaId;
        }

        @Override
        public String toString() {
            return positionType == NONE ? "超速" : "超速{类型" + positionType + ", ID " + areaId + "}";
        }
    }

    /**
     * 0x13 路段行驶时间不足 / 过长报警附加信息。
     * <pre>路段 ID DWORD | 路段行驶时间 WORD（秒） | 结果 BYTE（0 不足 1 过长）</pre>
     */
    public static final class SegmentTime implements Location.Extra {
        public static final int ID = 0x13;
        public static final int TOO_SHORT = 0;
        public static final int TOO_LONG = 1;

        public final long segmentId;
        public final int seconds;
        public final int result;

        public SegmentTime(long segmentId, int seconds, int result) {
            this.segmentId = segmentId;
            this.seconds = seconds;
            this.result = result;
        }

        public static SegmentTime decode(ByteBuf v) {
            return new SegmentTime(v.readUnsignedInt(), v.readUnsignedShort(), v.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out) {
            out.writeInt((int) segmentId);
            out.writeShort(seconds);
            out.writeByte(result);
        }

        public long getSegmentId() {
            return segmentId;
        }

        public int getSeconds() {
            return seconds;
        }

        public int getResult() {
            return result;
        }

        @Override
        public String toString() {
            return "路段" + segmentId + "行驶" + seconds + "秒(" + (result == TOO_LONG ? "过长" : "不足") + ")";
        }
    }
}
