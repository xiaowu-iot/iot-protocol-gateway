package io.github.xiaowuiot.iot.simulator.jt808;

import io.github.xiaowuiot.protocol.jt808.body.AlarmExtras;
import io.github.xiaowuiot.protocol.jt808.body.Area;
import io.github.xiaowuiot.protocol.jt808.body.AreaAlarmInfo;
import io.github.xiaowuiot.protocol.jt808.body.GeoUtils;
import io.github.xiaowuiot.protocol.jt808.body.Location;
import io.github.xiaowuiot.protocol.jt808.body.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 终端一侧的电子围栏判断：区域（圆形、矩形、多边形）和路线。
 * <pre>
 * 报警位                         什么时候置位                         清除
 * bit1  超速                     区域 / 路段限速，持续超过规定时间        条件解除
 * bit20 进出区域                 进、出区域（属性要求报给平台）           平台 0x8203 确认
 * bit21 进出路线                 进、出路线                            平台 0x8203 确认
 * bit22 路段行驶时间不足 / 过长    驶离一个路段时，用时不在阈值范围内        平台 0x8203 确认
 * bit23 路线偏离                 进入过路线之后又离开了                   回到路线上
 * </pre>
 * 每条位置最多带一个 0x11、一个 0x12、一个 0x13：同一时刻发生多个同类报警时只能报一个，这是附加信息格式本身的限制。
 */
class FenceJudge {

    private static final Logger log = LoggerFactory.getLogger(FenceJudge.class);

    static final long BIT_OVERSPEED = 1L << 1;
    static final long BIT_AREA = 1L << 20;
    static final long BIT_ROUTE = 1L << 21;
    static final long BIT_SEGMENT_TIME = 1L << 22;
    static final long BIT_DEVIATION = 1L << 23;

    private final String phone;
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private final Set<String> inside = new HashSet<>();
    private final Map<String, LocalDateTime> overSpeedSince = new HashMap<>();
    private final Map<Long, Route> routes = new LinkedHashMap<>();
    private final Map<Long, RouteState> routeStates = new HashMap<>();

    private static final class RouteState {
        boolean entered;
        // 从最后一个路段的终点离开：走完了整条路线，之后不算偏离
        boolean completed;
        int segment = -1;
        LocalDateTime segmentSince;
    }

    FenceJudge(String phone) {
        this.phone = phone;
    }

    // ==================== 设置 / 删除 ====================

    /**
     * 设置属性 0 更新：先删掉这一类的所有区域再加；1 追加；2 修改（同 ID 覆盖）
     */
    void setAreas(int type, int setType, Collection<? extends Area> items) {
        if (setType == 0) {
            removeAreas(type, null);
        }
        for (Area a : items) {
            areas.put(a.key(), a);
            inside.remove(a.key());
        }
        log.info("{} 设置{}，当前共 {} 个区域：{}", phone, typeName(type), areas.size(), areas.values());
    }

    /**
     * @param ids 为空表示删除这一类的全部区域
     */
    void removeAreas(int type, List<Long> ids) {
        areas.values().removeIf(a -> a.type() == type && (ids == null || ids.isEmpty() || ids.contains(a.id())));
        inside.removeIf(k -> !areas.containsKey(k));
        overSpeedSince.keySet().removeIf(k -> !areas.containsKey(k) && !k.startsWith("seg:"));
    }

    void setRoute(Route r) {
        routes.put(r.getId(), r);
        routeStates.put(r.getId(), new RouteState());
        log.info("{} 设置路线 {}：{} 个拐点 {}", phone, r.getId(), r.getPoints().size(), r.getPoints());
    }

    void removeRoutes(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            routes.clear();
            routeStates.clear();
        } else {
            ids.forEach(routes::remove);
            ids.forEach(routeStates::remove);
        }
        log.info("{} 删除路线，剩余 {} 条", phone, routes.size());
    }

    Route anyRoute() {
        return routes.isEmpty() ? null : routes.values().iterator().next();
    }

    // ==================== 判断 ====================

    /**
     * 判断这一条位置，把报警位、附加信息写进 l。
     *
     * @return 需要平台确认才清除的报警位（bit20 / 21 / 22），由调用方锁存
     */
    long check(Location l) {
        LocalDateTime t = l.getTime();
        double lat = l.getLatitude();
        double lng = l.getLongitude();
        double speed = l.getSpeed();
        long latch = 0;
        long state = 0;

        for (Area a : areas.values()) {
            if (!a.activeAt(t)) {
                continue;
            }
            boolean in = a.contains(lat, lng);
            boolean was = inside.contains(a.key());
            if (in != was) {
                if (in) {
                    inside.add(a.key());
                } else {
                    inside.remove(a.key());
                    overSpeedSince.remove(a.key());
                }
                boolean report = in ? (a.attr() & Area.ATTR_ENTER_TO_PLATFORM) != 0 : (a.attr() & Area.ATTR_EXIT_TO_PLATFORM) != 0;
                if (report) {
                    latch |= BIT_AREA;
                    l.putExtra(AreaAlarmInfo.ID, new AreaAlarmInfo(a.type(), a.id(), in ? AreaAlarmInfo.ENTER : AreaAlarmInfo.EXIT));
                    log.info("{} {}{} {}", phone, in ? "进入" : "离开", typeName(a.type()), a.id());
                }
            }
            if (in && (a.attr() & Area.ATTR_SPEED_LIMIT) != 0
                    && overSpeed(a.key(), speed, a.maxSpeed(), a.overSpeedSeconds(), t)) {
                state |= BIT_OVERSPEED;
                l.putExtra(AlarmExtras.Overspeed.ID, new AlarmExtras.Overspeed(a.type(), a.id()));
            }
        }

        for (Route r : routes.values()) {
            if (!r.activeAt(t)) {
                continue;
            }
            RouteState st = routeStates.get(r.getId());
            int seg = r.segmentAt(lat, lng);
            if (seg != st.segment) {
                if (st.segment >= 0 && seg >= 0) {
                    latch |= segmentTime(r, st, t, l);
                }
                if (st.segment < 0 && seg >= 0) {
                    st.entered = true;
                    st.completed = false;
                    if ((r.getAttr() & Route.ATTR_ENTER_TO_PLATFORM) != 0) {
                        latch |= BIT_ROUTE;
                        l.putExtra(AreaAlarmInfo.ID, new AreaAlarmInfo(AreaAlarmInfo.ROUTE, r.getId(), AreaAlarmInfo.ENTER));
                    }
                    log.info("{} 进入路线 {}，路段 {}", phone, r.getId(), r.getPoints().get(seg).segmentId);
                } else if (st.segment >= 0 && seg < 0) {
                    st.completed = st.segment == r.getPoints().size() - 2 && isAtRouteEnd(r, lat, lng);
                    if ((r.getAttr() & Route.ATTR_EXIT_TO_PLATFORM) != 0) {
                        latch |= BIT_ROUTE;
                        l.putExtra(AreaAlarmInfo.ID, new AreaAlarmInfo(AreaAlarmInfo.ROUTE, r.getId(), AreaAlarmInfo.EXIT));
                    }
                    log.info("{} {}路线 {}", phone, st.completed ? "走完" : "离开", r.getId());
                } else if (seg >= 0) {
                    log.info("{} 驶入路段 {}", phone, r.getPoints().get(seg).segmentId);
                }
                st.segment = seg;
                st.segmentSince = t;
            }
            if (seg < 0 && st.entered && !st.completed) {
                state |= BIT_DEVIATION;
            }
            if (seg >= 0) {
                Route.Point p = r.getPoints().get(seg);
                if (p.hasSpeedLimit() && overSpeed("seg:" + p.segmentId, speed, p.maxSpeed, p.overSpeedSeconds, t)) {
                    state |= BIT_OVERSPEED;
                    l.putExtra(AlarmExtras.Overspeed.ID, new AlarmExtras.Overspeed(AlarmExtras.Overspeed.SEGMENT, p.segmentId));
                } else if (!p.hasSpeedLimit() || speed <= p.maxSpeed) {
                    overSpeedSince.remove("seg:" + p.segmentId);
                }
            }
        }
        l.setAlarm(l.getAlarm() | state);
        return latch;
    }

    /**
     * 驶离一个路段、进入下一个路段时，检查在上一个路段用了多久
     */
    private long segmentTime(Route r, RouteState st, LocalDateTime t, Location l) {
        Route.Point p = r.getPoints().get(st.segment);
        if (!p.hasDriveTime() || st.segmentSince == null) {
            return 0;
        }
        int seconds = (int) Duration.between(st.segmentSince, t).getSeconds();
        int result;
        if (seconds > p.tooLongSeconds) {
            result = AlarmExtras.SegmentTime.TOO_LONG;
        } else if (seconds < p.tooShortSeconds) {
            result = AlarmExtras.SegmentTime.TOO_SHORT;
        } else {
            log.info("{} 路段 {} 用时 {} 秒，正常", phone, p.segmentId, seconds);
            return 0;
        }
        l.putExtra(AlarmExtras.SegmentTime.ID, new AlarmExtras.SegmentTime(p.segmentId, seconds, result));
        log.info("{} 路段 {} 用时 {} 秒，行驶{}（阈值 {}~{} 秒）", phone, p.segmentId, seconds,
                result == AlarmExtras.SegmentTime.TOO_LONG ? "过长" : "不足", p.tooShortSeconds, p.tooLongSeconds);
        return BIT_SEGMENT_TIME;
    }

    /**
     * 限速不是"超过就报"：要持续超过规定的时间，避免一脚油门就报警
     */
    private boolean overSpeed(String key, double speed, int max, int seconds, LocalDateTime t) {
        if (speed <= max) {
            overSpeedSince.remove(key);
            return false;
        }
        LocalDateTime since = overSpeedSince.computeIfAbsent(key, k -> t);
        return Duration.between(since, t).getSeconds() >= seconds;
    }

    /**
     * 走完了整条路线、从终点离开，不算偏离
     */
    private static boolean isAtRouteEnd(Route r, double lat, double lng) {
        Route.Point end = r.getPoints().get(r.getPoints().size() - 1);
        Route.Point before = r.getPoints().get(r.getPoints().size() - 2);
        return GeoUtils.distanceMeters(lat, lng, end.lat, end.lng) < before.width * 3;
    }

    private static String typeName(int type) {
        switch (type) {
            case AreaAlarmInfo.CIRCLE:
                return "圆形区域";
            case AreaAlarmInfo.RECTANGLE:
                return "矩形区域";
            case AreaAlarmInfo.POLYGON:
                return "多边形区域";
            default:
                return "路线";
        }
    }
}
