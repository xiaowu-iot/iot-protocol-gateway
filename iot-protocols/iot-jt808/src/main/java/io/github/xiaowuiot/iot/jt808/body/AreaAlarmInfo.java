package io.github.xiaowuiot.iot.jt808.body;

import io.netty.buffer.ByteBuf;

/**
 * 位置附加信息 0x12：进出区域 / 路线报警附加信息，配合报警位 bit20、bit21 使用。
 * <pre>位置类型 BYTE（1 圆形，2 矩形，3 多边形，4 路线）| 区域或路线 ID DWORD | 方向 BYTE（0 进，1 出）</pre>
 */
public class AreaAlarmInfo implements Location.Extra {

    public static final int ID = 0x12;

    public static final int CIRCLE = 1;
    public static final int RECTANGLE = 2;
    public static final int POLYGON = 3;
    public static final int ROUTE = 4;

    public static final int ENTER = 0;
    public static final int EXIT = 1;

    private final int areaType;
    private final long areaId;
    private final int direction;

    public AreaAlarmInfo(int areaType, long areaId, int direction) {
        this.areaType = areaType;
        this.areaId = areaId;
        this.direction = direction;
    }

    public static AreaAlarmInfo decode(ByteBuf v) {
        return new AreaAlarmInfo(v.readUnsignedByte(), v.readUnsignedInt(), v.readUnsignedByte());
    }

    @Override
    public void encode(ByteBuf out) {
        out.writeByte(areaType).writeInt((int) areaId).writeByte(direction);
    }

    public int getAreaType() {
        return areaType;
    }

    public long getAreaId() {
        return areaId;
    }

    public boolean isEnter() {
        return direction == ENTER;
    }

    @Override
    public String toString() {
        return "AreaAlarm{type=" + areaType + ", id=" + areaId + ", " + (isEnter() ? "进" : "出") + '}';
    }
}
