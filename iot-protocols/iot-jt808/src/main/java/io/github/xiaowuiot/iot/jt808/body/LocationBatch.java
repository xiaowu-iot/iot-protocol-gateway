package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 定位数据批量上传 0x0704，终端离线期间的位置数据，恢复连接后一次传上来。
 * <pre>数据项个数 WORD | 位置数据类型 BYTE（0 正常批量汇报，1 盲区补报）| [位置数据体长度 WORD + 位置数据体] × N</pre>
 * <b>盲区补报的数据不能按实时数据处理</b>：不能触发实时报警，存储时要按数据里的时间排序。
 */
public class LocationBatch implements Jt808Body {

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_BLIND_AREA = 1;

    private final int type;
    private final List<Location> locations;

    public LocationBatch(int type, List<Location> locations) {
        this.type = type;
        this.locations = locations;
    }

    public static LocationBatch decode(ByteBuf body, Jt808Header header) {
        int count = body.readUnsignedShort();
        int type = body.readUnsignedByte();
        List<Location> list = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= 2; i++) {
            int len = body.readUnsignedShort();
            list.add(Location.decode(body.readSlice(len), header));
        }
        return new LocationBatch(type, list);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeShort(locations.size());
        out.writeByte(type);
        for (Location l : locations) {
            int lengthIndex = out.writerIndex();
            out.writeShort(0);
            l.encode(out, header);
            out.setShort(lengthIndex, out.writerIndex() - lengthIndex - 2);
        }
    }

    public int getType() {
        return type;
    }

    public boolean isBlindArea() {
        return type == TYPE_BLIND_AREA;
    }

    public List<Location> getLocations() {
        return locations;
    }

    @Override
    public String toString() {
        return "LocationBatch{type=" + type + ", count=" + locations.size() + '}';
    }
}
