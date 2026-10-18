package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设置矩形区域 0x8602。格式和圆形区域 0x8600 几乎一样，只是"中心点 + 半径"换成了"左上点 + 右下点"。
 * <pre>
 * 设置属性 BYTE（0 更新 1 追加 2 修改） | 区域总数 BYTE |
 * [区域 ID DWORD | 区域属性 WORD | 左上点纬度 DWORD | 左上点经度 DWORD | 右下点纬度 DWORD | 右下点经度 DWORD |
 *  起始时间 BCD[6] | 结束时间 BCD[6]（区域属性 bit0 为 1 才有） | 最高速度 WORD | 超速持续时间 BYTE（bit1 为 1 才有）] × 区域总数
 * </pre>
 * 删除矩形区域 0x8603 的格式和 0x8601 一样，用 {@link CircleArea.Delete}。
 * <p>
 * 矩形的边是经线和纬线，是"经纬度矩形"，不是地面上任意方向的矩形。要斜着放的矩形只能用多边形。
 */
public class RectangleArea implements Jt808Body {

    public static final class Item implements Area {
        public final long id;
        public final int attr;
        public final double topLat;
        public final double leftLng;
        public final double bottomLat;
        public final double rightLng;
        public final Limits limits;

        public Item(long id, int attr, double topLat, double leftLng, double bottomLat, double rightLng, Limits limits) {
            this.id = id;
            this.attr = attr;
            this.topLat = topLat;
            this.leftLng = leftLng;
            this.bottomLat = bottomLat;
            this.rightLng = rightLng;
            this.limits = limits;
        }

        @Override
        public int type() {
            return AreaAlarmInfo.RECTANGLE;
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
        public boolean contains(double lat, double lng) {
            return lat <= topLat && lat >= bottomLat && lng >= leftLng && lng <= rightLng;
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

        @Override
        public String toString() {
            return "Rect{id=" + id + ", attr=" + attr + ", (" + topLat + "," + leftLng + ")-(" + bottomLat + "," + rightLng
                    + ")" + (maxSpeed() > 0 ? ", 限速" + maxSpeed() : "") + "}";
        }
    }

    private final int setType;
    private final List<Item> items;

    public RectangleArea(int setType, List<Item> items) {
        this.setType = setType;
        this.items = items;
    }

    public static RectangleArea decode(ByteBuf b, Jt808Header h) {
        int setType = b.readUnsignedByte();
        int n = b.readUnsignedByte();
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = b.readUnsignedInt();
            int attr = b.readUnsignedShort();
            double top = Area.readLat(b, attr);
            double left = Area.readLng(b, attr);
            double bottom = Area.readLat(b, attr);
            double right = Area.readLng(b, attr);
            items.add(new Item(id, attr, top, left, bottom, right, Area.Limits.read(b, attr)));
        }
        return new RectangleArea(setType, items);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        out.writeByte(setType);
        out.writeByte(items.size());
        for (Item it : items) {
            int attr = Area.hemisphere(it.attr, it.topLat, it.leftLng);
            out.writeInt((int) it.id);
            out.writeShort(attr);
            Area.writeCoord(out, it.topLat);
            Area.writeCoord(out, it.leftLng);
            Area.writeCoord(out, it.bottomLat);
            Area.writeCoord(out, it.rightLng);
            it.limits.write(out, attr);
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
        return "RectangleArea{set=" + setType + ", items=" + items + '}';
    }
}
