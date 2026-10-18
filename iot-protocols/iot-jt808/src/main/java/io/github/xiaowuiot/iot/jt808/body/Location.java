package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 位置信息汇报 0x0200。
 * <pre>
 * 报警标志 DWORD | 状态 DWORD | 纬度 DWORD | 经度 DWORD | 高程 WORD | 速度 WORD | 方向 WORD | 时间 BCD[6]
 * | 附加信息：ID BYTE + 长度 BYTE + 内容，重复多项
 * </pre>
 * 经纬度在报文里只有正数，南纬、西经由状态位 bit2、bit3 表示；这里解析成带符号的 double，编码时自动设置状态位。
 */
public class Location implements Jt808Body {

    /**
     * 附加信息 ID
     */
    public static final int EXTRA_MILEAGE = 0x01;
    public static final int EXTRA_FUEL = 0x02;
    public static final int EXTRA_RECORDER_SPEED = 0x03;
    public static final int EXTRA_SIGNAL_STATUS = 0x25;
    public static final int EXTRA_IO_STATUS = 0x2A;
    public static final int EXTRA_ANALOG = 0x2B;
    public static final int EXTRA_NETWORK_SIGNAL = 0x30;
    public static final int EXTRA_SATELLITES = 0x31;

    /**
     * 自定义附加信息的范围：0xE1 ~ 0xFF（0xE0 是"后续自定义信息长度"）
     */
    public static final int EXTRA_CUSTOM_START = 0xE1;

    /**
     * 附加信息 ID → 解析器。标准没有定义、厂家或者自己扩展的附加信息，在这里登记后就能解析成自己的类型
     */
    private static final Map<Integer, Function<ByteBuf, Object>> EXTRA_DECODERS = new ConcurrentHashMap<>();

    /**
     * 自定义的附加信息类型：只负责写内容，ID 和长度由 Location 写
     */
    public interface Extra {
        void encode(ByteBuf out);
    }

    /**
     * 登记附加信息解析器。value 只包含这一项的内容（不含 ID 和长度）
     */
    static {
        // 标准定义、结构比较复杂的附加信息，默认登记
        EXTRA_DECODERS.put(AlarmExtras.Overspeed.ID, AlarmExtras.Overspeed::decode);
        EXTRA_DECODERS.put(AreaAlarmInfo.ID, AreaAlarmInfo::decode);
        EXTRA_DECODERS.put(AlarmExtras.SegmentTime.ID, AlarmExtras.SegmentTime::decode);
    }

    public static void registerExtra(int id, Function<ByteBuf, Object> decoder) {
        EXTRA_DECODERS.put(id, decoder);
    }

    public static void unregisterExtra(int id) {
        EXTRA_DECODERS.remove(id);
    }

    private static final int STATUS_ACC = 0;
    private static final int STATUS_POSITIONED = 1;
    private static final int STATUS_SOUTH = 2;
    private static final int STATUS_WEST = 3;

    private long alarm;
    private long status;
    private double latitude;
    private double longitude;
    private int altitude;
    private double speed;
    private int direction;
    private LocalDateTime time;

    /**
     * 认识的附加信息解析成 Long，不认识的保留原始 byte[]，保持上报的顺序
     */
    private final Map<Integer, Object> extras = new LinkedHashMap<>();

    public static Location decode(ByteBuf body, Jt808Header header) {
        Location l = new Location();
        l.alarm = body.readUnsignedInt();
        l.status = body.readUnsignedInt();
        long lat = body.readUnsignedInt();
        long lng = body.readUnsignedInt();
        l.latitude = (l.statusBit(STATUS_SOUTH) ? -lat : lat) / 1_000_000.0;
        l.longitude = (l.statusBit(STATUS_WEST) ? -lng : lng) / 1_000_000.0;
        l.altitude = body.readUnsignedShort();
        l.speed = body.readUnsignedShort() / 10.0;
        l.direction = body.readUnsignedShort();
        l.time = BcdUtils.readBcdTime(body);
        while (body.readableBytes() >= 2) {
            int id = body.readUnsignedByte();
            int len = body.readUnsignedByte();
            if (len > body.readableBytes()) {
                // 长度不对说明附加信息坏了，前面的基本信息还能用，后面的丢弃
                break;
            }
            l.extras.put(id, readExtra(id, body.readSlice(len)));
        }
        return l;
    }

    /**
     * 登记过解析器的附加信息用解析器；没登记的，长度 1、2、4 的按无符号整数解析，其他长度保留原始字节。
     * <p>
     * 没登记时按长度而不是按 ID 判断类型：厂家自定义的附加信息大部分也是整数，按长度解析至少能看到数值。
     * 解析器抛异常（厂家的字段和约定的对不上）时，退回原始字节，不影响这条位置的其他信息。
     */
    private static Object readExtra(int id, ByteBuf value) {
        Function<ByteBuf, Object> decoder = EXTRA_DECODERS.get(id);
        if (decoder != null) {
            int start = value.readerIndex();
            try {
                return decoder.apply(value);
            } catch (RuntimeException e) {
                return ByteBufUtil.getBytes(value.readerIndex(start));
            }
        }
        switch (value.readableBytes()) {
            case 1:
                return (long) value.readUnsignedByte();
            case 2:
                return (long) value.readUnsignedShort();
            case 4:
                return value.readUnsignedInt();
            default:
                return ByteBufUtil.getBytes(value);
        }
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        long s = status;
        s = setBit(s, STATUS_SOUTH, latitude < 0);
        s = setBit(s, STATUS_WEST, longitude < 0);
        out.writeInt((int) alarm);
        out.writeInt((int) s);
        out.writeInt((int) Math.round(Math.abs(latitude) * 1_000_000));
        out.writeInt((int) Math.round(Math.abs(longitude) * 1_000_000));
        out.writeShort(altitude);
        out.writeShort((int) Math.round(speed * 10));
        out.writeShort(direction);
        BcdUtils.writeBcdTime(out, time);
        for (Map.Entry<Integer, Object> e : extras.entrySet()) {
            writeExtra(out, e.getKey(), e.getValue());
        }
    }

    private static void writeExtra(ByteBuf out, int id, Object value) {
        out.writeByte(id);
        if (value instanceof Extra) {
            // 先占位长度，写完内容再回填
            int lengthIndex = out.writerIndex();
            out.writeByte(0);
            ((Extra) value).encode(out);
            out.setByte(lengthIndex, out.writerIndex() - lengthIndex - 1);
            return;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            out.writeByte(bytes.length);
            out.writeBytes(bytes);
            return;
        }
        long v = ((Number) value).longValue();
        switch (extraLength(id)) {
            case 1:
                out.writeByte(1).writeByte((int) v);
                break;
            case 2:
                out.writeByte(2).writeShort((int) v);
                break;
            default:
                out.writeByte(4).writeInt((int) v);
        }
    }

    private static int extraLength(int id) {
        switch (id) {
            case EXTRA_NETWORK_SIGNAL:
            case EXTRA_SATELLITES:
                return 1;
            case EXTRA_FUEL:
            case EXTRA_RECORDER_SPEED:
            case EXTRA_IO_STATUS:
                return 2;
            default:
                return 4;
        }
    }

    private boolean statusBit(int bit) {
        return (status >> bit & 1) == 1;
    }

    private static long setBit(long value, int bit, boolean on) {
        return on ? value | 1L << bit : value & ~(1L << bit);
    }

    public boolean hasAlarm(int bit) {
        return (alarm >> bit & 1) == 1;
    }

    public boolean isAccOn() {
        return statusBit(STATUS_ACC);
    }

    public boolean isPositioned() {
        return statusBit(STATUS_POSITIONED);
    }

    /**
     * 里程，单位 km；没有上报时返回 null
     */
    public Double getMileageKm() {
        Object v = extras.get(EXTRA_MILEAGE);
        return v instanceof Long ? (Long) v / 10.0 : null;
    }

    public Integer getSatellites() {
        Object v = extras.get(EXTRA_SATELLITES);
        return v instanceof Long ? ((Long) v).intValue() : null;
    }

    public long getAlarm() {
        return alarm;
    }

    public Location setAlarm(long alarm) {
        this.alarm = alarm;
        return this;
    }

    public long getStatus() {
        return status;
    }

    public Location setStatus(long status) {
        this.status = status;
        return this;
    }

    public double getLatitude() {
        return latitude;
    }

    public Location setLatitude(double latitude) {
        this.latitude = latitude;
        return this;
    }

    public double getLongitude() {
        return longitude;
    }

    public Location setLongitude(double longitude) {
        this.longitude = longitude;
        return this;
    }

    public int getAltitude() {
        return altitude;
    }

    public Location setAltitude(int altitude) {
        this.altitude = altitude;
        return this;
    }

    public double getSpeed() {
        return speed;
    }

    public Location setSpeed(double speed) {
        this.speed = speed;
        return this;
    }

    public int getDirection() {
        return direction;
    }

    public Location setDirection(int direction) {
        this.direction = direction;
        return this;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public Location setTime(LocalDateTime time) {
        this.time = time;
        return this;
    }

    public Map<Integer, Object> getExtras() {
        return Collections.unmodifiableMap(extras);
    }

    public Location putExtra(int id, Object value) {
        extras.put(id, value);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Location{");
        sb.append(String.format("lat=%.6f, lng=%.6f, speed=%.1f, dir=%d, alt=%d, time=%s, acc=%s, positioned=%s",
                latitude, longitude, speed, direction, altitude, time, isAccOn(), isPositioned()));
        if (alarm != 0) {
            sb.append(String.format(", alarm=0x%08X", alarm));
        }
        for (Map.Entry<Integer, Object> e : extras.entrySet()) {
            Object v = e.getValue();
            sb.append(String.format(", 0x%02X=%s", e.getKey(),
                    v instanceof byte[] ? ByteBufUtil.hexDump((byte[]) v) : v));
        }
        return sb.append('}').toString();
    }
}
