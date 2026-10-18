package io.github.xiaowuiot.iot.jt808.body;

/**
 * 经纬度计算。
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoUtils() {
    }

    /**
     * 两点之间的球面距离（米），haversine 公式。几公里范围内的电子围栏，误差在米级，足够用
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    /**
     * 点到线段的最短距离（米）。以点为原点把经纬度投影成平面坐标（经度方向乘 cos(纬度)），几公里内误差很小
     */
    public static double distanceToSegmentMeters(double lat, double lng, double lat1, double lng1, double lat2, double lng2) {
        double k = Math.cos(Math.toRadians(lat));
        double ax = (lng1 - lng) * k;
        double ay = lat1 - lat;
        double bx = (lng2 - lng) * k;
        double by = lat2 - lat;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double px = ax + t * dx;
        double py = ay + t * dy;
        return Math.sqrt(px * px + py * py) * Math.toRadians(1) * EARTH_RADIUS_METERS;
    }
}
