package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;

/**
 * 电子围栏的公共部分：圆形、矩形、多边形区域都有"区域属性"，以及按属性决定有没有的时间段、限速字段。
 * <pre>
 * 区域属性 WORD：
 *   bit0 根据时间       bit1 限速
 *   bit2 进区域报警给驾驶员   bit3 进区域报警给平台
 *   bit4 出区域报警给驾驶员   bit5 出区域报警给平台
 *   bit6 0 北纬 1 南纬       bit7 0 东经 1 西经
 *   bit8 0 允许开门 1 禁止开门
 *   bit14 0 进区域开启通信模块 1 进区域关闭通信模块
 *   bit15 0 进区域不采集 GNSS 详细定位数据 1 采集
 * </pre>
 * 区域 ID 在每种区域内部唯一：圆形区域 1 和矩形区域 1 是两个不同的区域，终端、平台都要按"类型 + ID"区分。
 */
public interface Area {

    int ATTR_BY_TIME = 1;
    int ATTR_SPEED_LIMIT = 1 << 1;
    int ATTR_ENTER_TO_DRIVER = 1 << 2;
    int ATTR_ENTER_TO_PLATFORM = 1 << 3;
    int ATTR_EXIT_TO_DRIVER = 1 << 4;
    int ATTR_EXIT_TO_PLATFORM = 1 << 5;
    int ATTR_SOUTH = 1 << 6;
    int ATTR_WEST = 1 << 7;
    int ATTR_NO_DOOR_OPEN = 1 << 8;

    /**
     * {@link AreaAlarmInfo#CIRCLE} / RECTANGLE / POLYGON / ROUTE
     */
    int type();

    long id();

    int attr();

    boolean contains(double lat, double lng);

    /**
     * 区域限速，没有限速返回 0
     */
    int maxSpeed();

    int overSpeedSeconds();

    /**
     * 当前时间是否在区域的生效时间段内。没有设置"根据时间"的区域一直生效
     */
    boolean activeAt(LocalDateTime t);

    /**
     * 类型 + ID，区分不同类型的同号区域
     */
    default String key() {
        return type() + ":" + id();
    }

    /**
     * 时间段、限速：按区域属性决定有没有。读写顺序固定是"起止时间在前，限速在后"
     */
    final class Limits {
        public final LocalDateTime start;
        public final LocalDateTime end;
        public final int maxSpeed;
        public final int overSpeedSeconds;

        public static final Limits NONE = new Limits(null, null, 0, 0);

        public Limits(LocalDateTime start, LocalDateTime end, int maxSpeed, int overSpeedSeconds) {
            this.start = start;
            this.end = end;
            this.maxSpeed = maxSpeed;
            this.overSpeedSeconds = overSpeedSeconds;
        }

        public static Limits read(ByteBuf b, int attr) {
            LocalDateTime start = null;
            LocalDateTime end = null;
            if ((attr & ATTR_BY_TIME) != 0) {
                start = BcdUtils.readBcdTime(b);
                end = BcdUtils.readBcdTime(b);
            }
            int maxSpeed = 0;
            int duration = 0;
            if ((attr & ATTR_SPEED_LIMIT) != 0) {
                maxSpeed = b.readUnsignedShort();
                duration = b.readUnsignedByte();
            }
            return new Limits(start, end, maxSpeed, duration);
        }

        public void write(ByteBuf out, int attr) {
            if ((attr & ATTR_BY_TIME) != 0) {
                BcdUtils.writeBcdTime(out, start);
                BcdUtils.writeBcdTime(out, end);
            }
            if ((attr & ATTR_SPEED_LIMIT) != 0) {
                out.writeShort(maxSpeed);
                out.writeByte(overSpeedSeconds);
            }
        }

        /**
         * 808 规定：起止时间的年月日如果是 0，表示每天的这个时间段，比如 00-00-00 08:00:00 ~ 00-00-00 18:00:00。
         * BCD 解析时全 0 的日期解析不出来，这里只处理具体时间段；每天重复的时段留作作业
         */
        public boolean activeAt(LocalDateTime t, int attr) {
            if ((attr & ATTR_BY_TIME) == 0 || start == null || end == null) {
                return true;
            }
            return !t.isBefore(start) && !t.isAfter(end);
        }
    }

    static double readLat(ByteBuf b, int attr) {
        double v = b.readUnsignedInt() / 1_000_000.0;
        return (attr & ATTR_SOUTH) != 0 ? -v : v;
    }

    static double readLng(ByteBuf b, int attr) {
        double v = b.readUnsignedInt() / 1_000_000.0;
        return (attr & ATTR_WEST) != 0 ? -v : v;
    }

    /**
     * 按坐标的正负设置南纬、西经标志。808 的经纬度字段是无符号数，正负只能靠属性位表示
     */
    static int hemisphere(int attr, double lat, double lng) {
        return attr & ~(ATTR_SOUTH | ATTR_WEST) | (lat < 0 ? ATTR_SOUTH : 0) | (lng < 0 ? ATTR_WEST : 0);
    }

    static void writeCoord(ByteBuf out, double v) {
        out.writeInt((int) Math.round(Math.abs(v) * 1_000_000));
    }
}
