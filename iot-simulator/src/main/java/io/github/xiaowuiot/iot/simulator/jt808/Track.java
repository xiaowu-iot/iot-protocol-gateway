package io.github.xiaowuiot.iot.simulator.jt808;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟行驶轨迹：从起点出发，按速度和方向前进，每隔一段随机转个弯。
 * <p>
 * 不追求真实路网，目的是让平台收到"会动的、方向和速度合理的"位置，画出来是一条连续的线。
 */
public class Track {

    /**
     * 1 度纬度约等于 111 km
     */
    private static final double METERS_PER_DEGREE = 111_000;

    private double lat;
    private double lng;
    private double speed;
    private int direction;
    private double mileageKm;
    private int stoppedSeconds;
    private int minSpeed = 0;
    private int maxSpeed = 120;
    private List<double[]> waypoints;
    private int waypointIndex;

    public Track(double lat, double lng) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        this.lat = lat;
        this.lng = lng;
        this.speed = 20 + r.nextInt(60);
        this.direction = r.nextInt(360);
    }

    /**
     * 前进 seconds 秒
     */
    public void move(int seconds) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (stoppedSeconds > 0) {
            speed = 0;
            stoppedSeconds -= seconds;
            if (stoppedSeconds <= 0) {
                speed = 20 + r.nextInt(20);
            }
            return;
        }
        if (r.nextInt(10) == 0) {
            direction = (direction + r.nextInt(-90, 91) + 360) % 360;
        }
        speed = Math.max(minSpeed, Math.min(maxSpeed, speed + r.nextInt(-10, 11)));
        double meters = speed / 3.6 * seconds;
        if (waypoints != null) {
            followStep(meters);
            return;
        }
        double rad = Math.toRadians(direction);
        // 正北为 0 度，顺时针：北向分量是 cos，东向分量是 sin
        lat += meters * Math.cos(rad) / METERS_PER_DEGREE;
        lng += meters * Math.sin(rad) / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        mileageKm += meters / 1000;
    }

    /**
     * 原地停车 seconds 秒（加油、装卸货），之后继续行驶
     */
    public void stop(int seconds) {
        stoppedSeconds = seconds;
        speed = 0;
    }

    /**
     * 限制车速范围，比如洒水车作业时 10~25 km/h
     */
    public void setSpeedRange(int min, int max) {
        this.minSpeed = min;
        this.maxSpeed = max;
        speed = Math.max(min, Math.min(max, speed));
    }

    public boolean isStopped() {
        return stoppedSeconds > 0;
    }

    /**
     * 沿着一串拐点开（lat, lng），开到最后一个点后恢复随机行驶
     */
    public void follow(List<double[]> points) {
        this.waypoints = points;
        this.waypointIndex = 0;
    }

    /**
     * 不再沿路线走，右转 90 度离开
     */
    public void deviate() {
        waypoints = null;
        direction = (direction + 90) % 360;
    }

    public boolean isFollowing() {
        return waypoints != null;
    }

    private void followStep(double meters) {
        double[] wp = waypoints.get(waypointIndex);
        double dy = (wp[0] - lat) * METERS_PER_DEGREE;
        double dx = (wp[1] - lng) * METERS_PER_DEGREE * Math.cos(Math.toRadians(lat));
        double dist = Math.sqrt(dx * dx + dy * dy);
        direction = (int) Math.round((Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360);
        if (meters >= dist) {
            lat = wp[0];
            lng = wp[1];
            mileageKm += dist / 1000;
            if (++waypointIndex >= waypoints.size()) {
                waypoints = null;
            }
            return;
        }
        lat += dy / dist * meters / METERS_PER_DEGREE;
        lng += dx / dist * meters / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        mileageKm += meters / 1000;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public double getSpeed() {
        return speed;
    }

    public int getDirection() {
        return direction;
    }

    public double getMileageKm() {
        return mileageKm;
    }
}
