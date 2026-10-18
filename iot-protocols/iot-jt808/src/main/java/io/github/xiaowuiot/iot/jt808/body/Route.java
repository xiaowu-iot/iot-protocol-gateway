package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设置路线 0x8606。路线由拐点组成，<b>每个拐点到下一个拐点是一个路段</b>，路段的宽度、限速、行驶时间阈值写在起点那个拐点上。
 * <pre>
 * 路线 ID DWORD | 路线属性 WORD | 起始时间 BCD[6] | 结束时间 BCD[6]（路线属性 bit0 为 1 才有） | 拐点数 WORD |
 * [拐点 ID DWORD | 路段 ID DWORD | 拐点纬度 DWORD | 拐点经度 DWORD | 路段宽度 BYTE（米） | 路段属性 BYTE |
 *  路段行驶过长阈值 WORD | 路段行驶不足阈值 WORD（秒，路段属性 bit0 为 1 才有） |
 *  路段最高速度 WORD | 路段超速持续时间 BYTE（路段属性 bit1 为 1 才有）] × 拐点数
 *
 * 路线属性：bit0 根据时间，bit2 进路线报警给驾驶员，bit3 进路线报警给平台，bit4 出路线报警给驾驶员，bit5 出路线报警给平台
 * 路段属性：bit0 行驶时间，bit1 限速，bit2 0 北纬 1 南纬，bit3 0 东经 1 西经
 * </pre>
 * 和区域不同，路线的时间段在路线上，限速和行驶时间在每个路段上：同一条线路，市区路段限速 40、高速路段限速 80。
 * 删除路线 0x8607 用 {@link CircleArea.Delete}。
 */
public class Route implements Jt808Body {

    public static final int ATTR_BY_TIME = 1;
    public static final int ATTR_ENTER_TO_PLATFORM = 1 << 3;
    public static final int ATTR_EXIT_TO_PLATFORM = 1 << 5;

    public static final int SEG_DRIVE_TIME = 1;
    public static final int SEG_SPEED_LIMIT = 1 << 1;
    public static final int SEG_SOUTH = 1 << 2;
    public static final int SEG_WEST = 1 << 3;

    public static final class Point {
        public final long pointId;
        public final long segmentId;
        public final double lat;
        public final double lng;
        public final int width;
        public final int segAttr;
        /**
         * 路段行驶时间超过这个值（秒）报"行驶过长"
         */
        public final int tooLongSeconds;
        /**
         * 路段行驶时间少于这个值（秒）报"行驶不足"：开得太快，可能超速或者抄近道
         */
        public final int tooShortSeconds;
        public final int maxSpeed;
        public final int overSpeedSeconds;

        public Point(long pointId, long segmentId, double lat, double lng, int width, int segAttr,
                     int tooLongSeconds, int tooShortSeconds, int maxSpeed, int overSpeedSeconds) {
            this.pointId = pointId;
            this.segmentId = segmentId;
            this.lat = lat;
            this.lng = lng;
            this.width = width;
            this.segAttr = segAttr;
            this.tooLongSeconds = tooLongSeconds;
            this.tooShortSeconds = tooShortSeconds;
            this.maxSpeed = maxSpeed;
            this.overSpeedSeconds = overSpeedSeconds;
        }

        public boolean hasDriveTime() {
            return (segAttr & SEG_DRIVE_TIME) != 0;
        }

        public boolean hasSpeedLimit() {
            return (segAttr & SEG_SPEED_LIMIT) != 0;
        }

        @Override
        public String toString() {
            return "P" + pointId + "/S" + segmentId + "(" + lat + "," + lng + ", 宽" + width + "m"
                    + (hasDriveTime() ? ", 时间 " + tooShortSeconds + "~" + tooLongSeconds + "s" : "")
                    + (hasSpeedLimit() ? ", 限速" + maxSpeed : "") + ")";
        }
    }

    private final long id;
    private final int attr;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private final List<Point> points;

    public Route(long id, int attr, LocalDateTime start, LocalDateTime end, List<Point> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("路线至少 2 个拐点");
        }
        this.id = id;
        this.attr = attr;
        this.start = start;
        this.end = end;
        this.points = points;
    }

    public static Route decode(ByteBuf b, Jt808Header h) {
        long id = b.readUnsignedInt();
        int attr = b.readUnsignedShort();
        LocalDateTime start = null;
        LocalDateTime end = null;
        if ((attr & ATTR_BY_TIME) != 0) {
            start = BcdUtils.readBcdTime(b);
            end = BcdUtils.readBcdTime(b);
        }
        int n = b.readUnsignedShort();
        List<Point> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long pointId = b.readUnsignedInt();
            long segId = b.readUnsignedInt();
            double lat = b.readUnsignedInt() / 1_000_000.0;
            double lng = b.readUnsignedInt() / 1_000_000.0;
            int width = b.readUnsignedByte();
            int segAttr = b.readUnsignedByte();
            int tooLong = 0;
            int tooShort = 0;
            if ((segAttr & SEG_DRIVE_TIME) != 0) {
                tooLong = b.readUnsignedShort();
                tooShort = b.readUnsignedShort();
            }
            int maxSpeed = 0;
            int duration = 0;
            if ((segAttr & SEG_SPEED_LIMIT) != 0) {
                maxSpeed = b.readUnsignedShort();
                duration = b.readUnsignedByte();
            }
            // 南纬、西经标志在路段属性里，不在路线属性里
            points.add(new Point(pointId, segId, (segAttr & SEG_SOUTH) != 0 ? -lat : lat,
                    (segAttr & SEG_WEST) != 0 ? -lng : lng, width, segAttr, tooLong, tooShort, maxSpeed, duration));
        }
        return new Route(id, attr, start, end, points);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        out.writeInt((int) id);
        out.writeShort(attr);
        if ((attr & ATTR_BY_TIME) != 0) {
            BcdUtils.writeBcdTime(out, start);
            BcdUtils.writeBcdTime(out, end);
        }
        out.writeShort(points.size());
        for (Point p : points) {
            out.writeInt((int) p.pointId);
            out.writeInt((int) p.segmentId);
            Area.writeCoord(out, p.lat);
            Area.writeCoord(out, p.lng);
            out.writeByte(p.width);
            out.writeByte(p.segAttr & ~(SEG_SOUTH | SEG_WEST) | (p.lat < 0 ? SEG_SOUTH : 0) | (p.lng < 0 ? SEG_WEST : 0));
            if (p.hasDriveTime()) {
                out.writeShort(p.tooLongSeconds);
                out.writeShort(p.tooShortSeconds);
            }
            if (p.hasSpeedLimit()) {
                out.writeShort(p.maxSpeed);
                out.writeByte(p.overSpeedSeconds);
            }
        }
    }

    /**
     * 点在哪个路段上：到路段（线段）的距离不超过路段宽度的一半。不在任何路段上返回 -1。
     * <p>
     * "路段宽度"各家终端理解不一：有的按宽度的一半作为偏离阈值（路段中心线两侧各一半），有的直接按宽度。
     * 对接时要和终端厂家确认，否则平台画出来的走廊和终端的判断对不上
     */
    public int segmentAt(double lat, double lng) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            double d = GeoUtils.distanceToSegmentMeters(lat, lng, a.lat, a.lng, b.lat, b.lng);
            if (d <= a.width / 2.0 && d < bestDist) {
                best = i;
                bestDist = d;
            }
        }
        return best;
    }

    public boolean activeAt(LocalDateTime t) {
        if ((attr & ATTR_BY_TIME) == 0 || start == null || end == null) {
            return true;
        }
        return !t.isBefore(start) && !t.isAfter(end);
    }

    public long getId() {
        return id;
    }

    public int getAttr() {
        return attr;
    }

    public List<Point> getPoints() {
        return Collections.unmodifiableList(points);
    }

    @Override
    public String toString() {
        return "Route{id=" + id + ", attr=" + attr + ", points=" + points + '}';
    }
}
