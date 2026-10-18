package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设置多边形区域 0x8604。<b>一条消息只设置一个区域</b>，没有"设置属性"和"区域总数"，这一点和圆形、矩形不一样。
 * <pre>
 * 区域 ID DWORD | 区域属性 WORD | 起始时间 BCD[6] | 结束时间 BCD[6] | 最高速度 WORD | 超速持续时间 BYTE |
 * 区域总顶点数 WORD | [顶点纬度 DWORD | 顶点经度 DWORD] × 顶点数
 * </pre>
 * 顶点多的时候消息体会超过 1023 字节（一个顶点 8 字节，120 多个顶点就超了），要分包。
 * 删除多边形区域 0x8605 用 {@link CircleArea.Delete}。
 */
public class PolygonArea implements Jt808Body, Area {

    public static final class Point {
        public final double lat;
        public final double lng;

        public Point(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
        }

        @Override
        public String toString() {
            return lat + "," + lng;
        }
    }

    private final long id;
    private final int attr;
    private final Limits limits;
    private final List<Point> points;

    public PolygonArea(long id, int attr, Limits limits, List<Point> points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("多边形至少 3 个顶点");
        }
        this.id = id;
        this.attr = attr;
        this.limits = limits;
        this.points = points;
    }

    public static PolygonArea decode(ByteBuf b, Jt808Header h) {
        long id = b.readUnsignedInt();
        int attr = b.readUnsignedShort();
        Limits limits = Limits.read(b, attr);
        int n = b.readUnsignedShort();
        List<Point> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(new Point(Area.readLat(b, attr), Area.readLng(b, attr)));
        }
        return new PolygonArea(id, attr, limits, points);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        int a = Area.hemisphere(attr, points.get(0).lat, points.get(0).lng);
        out.writeInt((int) id);
        out.writeShort(a);
        limits.write(out, a);
        out.writeShort(points.size());
        for (Point p : points) {
            Area.writeCoord(out, p.lat);
            Area.writeCoord(out, p.lng);
        }
    }

    /**
     * 射线法：从点向东发一条射线，和多边形的边相交奇数次就在里面。
     * 几公里范围内把经纬度当平面坐标用，误差可以忽略；跨 180 度经线的多边形这里不处理
     */
    @Override
    public boolean contains(double lat, double lng) {
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            Point a = points.get(i);
            Point b = points.get(j);
            if ((a.lat > lat) != (b.lat > lat)
                    && lng < (b.lng - a.lng) * (lat - a.lat) / (b.lat - a.lat) + a.lng) {
                inside = !inside;
            }
        }
        return inside;
    }

    @Override
    public int type() {
        return AreaAlarmInfo.POLYGON;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public int attr() {
        return attr;
    }

    @Override
    public int maxSpeed() {
        return limits.maxSpeed;
    }

    @Override
    public int overSpeedSeconds() {
        return limits.overSpeedSeconds;
    }

    @Override
    public boolean activeAt(LocalDateTime t) {
        return limits.activeAt(t, attr);
    }

    public List<Point> getPoints() {
        return Collections.unmodifiableList(points);
    }

    @Override
    public String toString() {
        return "Polygon{id=" + id + ", attr=" + attr + ", points=" + points + (maxSpeed() > 0 ? ", 限速" + maxSpeed() : "") + "}";
    }
}
