package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设置圆形区域 0x8600（按 2013 版实现）。
 * <pre>
 * 设置属性 BYTE（0 更新：删除终端已有的全部区域再添加，1 追加，2 修改）| 区域总数 BYTE | 区域项 × 总数
 * 区域项：区域 ID DWORD | 区域属性 WORD | 中心纬度 DWORD | 中心经度 DWORD | 半径 DWORD（米）
 *        | [起始时间 BCD[6] | 结束时间 BCD[6]]（区域属性 bit0 为 1 时才有）
 *        | [最高速度 WORD | 超速持续时间 BYTE]（区域属性 bit1 为 1 时才有）
 * </pre>
 * 区域属性：bit0 根据时间，bit1 限速，bit2 进区域报警给驾驶员，bit3 进区域报警给平台，
 * bit4 出区域报警给驾驶员，bit5 出区域报警给平台，bit6 南纬，bit7 西经，bit8 禁止开门。
 * <p>
 * 2019 版在区域项末尾增加了字段（夜间限速、区域名称等），编码前要按终端版本处理，这里没有实现。
 */
public class CircleArea implements Jt808Body {

    public static final int SET_UPDATE = 0;
    public static final int SET_APPEND = 1;
    public static final int SET_MODIFY = 2;

    public static final int ATTR_BY_TIME = 1;
    public static final int ATTR_SPEED_LIMIT = 1 << 1;
    public static final int ATTR_ENTER_TO_DRIVER = 1 << 2;
    public static final int ATTR_ENTER_TO_PLATFORM = 1 << 3;
    public static final int ATTR_EXIT_TO_DRIVER = 1 << 4;
    public static final int ATTR_EXIT_TO_PLATFORM = 1 << 5;

    public static final class Item implements Area {
        public final long id;
        public final int attr;
        public final double lat;
        public final double lng;
        public final long radiusMeters;
        public final LocalDateTime start;
        public final LocalDateTime end;
        public final int maxSpeed;
        public final int overSpeedSeconds;

        public Item(long id, int attr, double lat, double lng, long radiusMeters,
                    LocalDateTime start, LocalDateTime end, int maxSpeed, int overSpeedSeconds) {
            this.id = id;
            this.attr = attr;
            this.lat = lat;
            this.lng = lng;
            this.radiusMeters = radiusMeters;
            this.start = start;
            this.end = end;
            this.maxSpeed = maxSpeed;
            this.overSpeedSeconds = overSpeedSeconds;
        }

        /**
         * 只设进出区域报警，不限时间、不限速
         */
        public static Item simple(long id, double lat, double lng, long radiusMeters) {
            return new Item(id, ATTR_ENTER_TO_PLATFORM | ATTR_EXIT_TO_PLATFORM, lat, lng, radiusMeters,
                    null, null, 0, 0);
        }

        @Override
        public boolean contains(double pointLat, double pointLng) {
            return GeoUtils.distanceMeters(lat, lng, pointLat, pointLng) <= radiusMeters;
        }

        @Override
        public int type() {
            return AreaAlarmInfo.CIRCLE;
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
            return maxSpeed;
        }

        @Override
        public int overSpeedSeconds() {
            return overSpeedSeconds;
        }

        @Override
        public boolean activeAt(LocalDateTime t) {
            return new Limits(start, end, maxSpeed, overSpeedSeconds).activeAt(t, attr);
        }

        @Override
        public String toString() {
            return "Circle{id=" + id + ", attr=" + attr + ", center=" + lat + "," + lng + ", r=" + radiusMeters + "m}";
        }
    }

    private final int setType;
    private final List<Item> items;

    public CircleArea(int setType, List<Item> items) {
        this.setType = setType;
        this.items = items;
    }

    public static CircleArea decode(ByteBuf b, Jt808Header h) {
        int setType = b.readUnsignedByte();
        int n = b.readUnsignedByte();
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = b.readUnsignedInt();
            int attr = b.readUnsignedShort();
            double lat = b.readUnsignedInt() / 1_000_000.0;
            double lng = b.readUnsignedInt() / 1_000_000.0;
            long radius = b.readUnsignedInt();
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
            if ((attr & 1 << 6) != 0) {
                lat = -lat;
            }
            if ((attr & 1 << 7) != 0) {
                lng = -lng;
            }
            items.add(new Item(id, attr, lat, lng, radius, start, end, maxSpeed, duration));
        }
        return new CircleArea(setType, items);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        out.writeByte(setType);
        out.writeByte(items.size());
        for (Item it : items) {
            int attr = it.attr & ~(1 << 6 | 1 << 7);
            if (it.lat < 0) {
                attr |= 1 << 6;
            }
            if (it.lng < 0) {
                attr |= 1 << 7;
            }
            out.writeInt((int) it.id);
            out.writeShort(attr);
            out.writeInt((int) Math.round(Math.abs(it.lat) * 1_000_000));
            out.writeInt((int) Math.round(Math.abs(it.lng) * 1_000_000));
            out.writeInt((int) it.radiusMeters);
            if ((attr & ATTR_BY_TIME) != 0) {
                BcdUtils.writeBcdTime(out, it.start);
                BcdUtils.writeBcdTime(out, it.end);
            }
            if ((attr & ATTR_SPEED_LIMIT) != 0) {
                out.writeShort(it.maxSpeed);
                out.writeByte(it.overSpeedSeconds);
            }
        }
    }

    public int getSetType() {
        return setType;
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "CircleArea{set=" + setType + ", items=" + items + '}';
    }

    /**
     * 删除圆形区域 0x8601：区域数 BYTE | 区域 ID DWORD × 区域数。区域数为 0 表示删除全部圆形区域
     */
    public static final class Delete implements Jt808Body {
        private final List<Long> ids;

        public Delete(List<Long> ids) {
            this.ids = ids;
        }

        public static Delete decode(ByteBuf b, Jt808Header h) {
            int n = b.readUnsignedByte();
            List<Long> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ids.add(b.readUnsignedInt());
            }
            return new Delete(ids);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(ids.size());
            for (Long id : ids) {
                out.writeInt((int) id.longValue());
            }
        }

        public List<Long> getIds() {
            return Collections.unmodifiableList(ids);
        }
    }
}
