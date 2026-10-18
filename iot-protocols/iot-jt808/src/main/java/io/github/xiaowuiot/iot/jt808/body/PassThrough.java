package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 数据透传：数据上行透传 0x0900、数据下行透传 0x8900，格式一样。
 * <pre>透传消息类型 BYTE | 透传消息内容（剩余全部字节）</pre>
 * 808 终端只当"搬运工"：外接设备（称重仪表、温度计、油耗传感器……）的数据原样装进来，808 不解释内容。
 * <p>
 * 透传消息类型：0x00 GNSS 模块详细定位数据，0x0B 道路运输证 IC 卡信息，0x41 串口 1 透传，0x42 串口 2 透传，
 * 0xF0 ~ 0xFF 用户自定义透传。
 * <p>
 * 内容怎么解析由外接设备的协议决定，按透传类型登记解析器；没登记的保留原始字节。
 */
public class PassThrough implements Jt808Body {

    public static final int TYPE_GNSS_DETAIL = 0x00;
    public static final int TYPE_IC_CARD = 0x0B;
    public static final int TYPE_SERIAL_1 = 0x41;
    public static final int TYPE_SERIAL_2 = 0x42;
    public static final int TYPE_CUSTOM_START = 0xF0;

    /**
     * 透传类型 → 内容解析器。注意解析器是按"上行 / 下行"分开登记的：
     * 同一个串口，上行是仪表发来的读数，下行是平台发给仪表的指令，格式往往不一样
     */
    private static final Map<Integer, Function<byte[], Object>> UP_PARSERS = new ConcurrentHashMap<>();
    private static final Map<Integer, Function<byte[], Object>> DOWN_PARSERS = new ConcurrentHashMap<>();

    private final int type;
    private final byte[] content;

    /**
     * 解析后的内容；没有登记解析器、或者解析失败时为 null
     */
    private final Object parsed;

    public PassThrough(int type, byte[] content) {
        this(type, content, null);
    }

    private PassThrough(int type, byte[] content, Object parsed) {
        this.type = type;
        this.content = content;
        this.parsed = parsed;
    }

    public static void registerUplink(int type, Function<byte[], Object> parser) {
        UP_PARSERS.put(type, parser);
    }

    public static void registerDownlink(int type, Function<byte[], Object> parser) {
        DOWN_PARSERS.put(type, parser);
    }

    public static void unregister(int type) {
        UP_PARSERS.remove(type);
        DOWN_PARSERS.remove(type);
    }

    public static PassThrough decodeUplink(ByteBuf body, Jt808Header header) {
        return decode(body, UP_PARSERS);
    }

    public static PassThrough decodeDownlink(ByteBuf body, Jt808Header header) {
        return decode(body, DOWN_PARSERS);
    }

    private static PassThrough decode(ByteBuf body, Map<Integer, Function<byte[], Object>> parsers) {
        int type = body.readUnsignedByte();
        byte[] content = ByteBufUtil.getBytes(body);
        Function<byte[], Object> parser = parsers.get(type);
        Object parsed = null;
        if (parser != null) {
            try {
                parsed = parser.apply(content);
            } catch (RuntimeException e) {
                // 外接设备的数据格式不对，不影响 808 这一层：原始字节还在，交给下游
                parsed = null;
            }
        }
        return new PassThrough(type, content, parsed);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeByte(type);
        out.writeBytes(content);
    }

    public int getType() {
        return type;
    }

    public byte[] getContent() {
        return content;
    }

    @SuppressWarnings("unchecked")
    public <T> T getParsed() {
        return (T) parsed;
    }

    @Override
    public String toString() {
        return String.format("PassThrough{type=0x%02X, %d 字节, %s}", type, content.length,
                parsed != null ? parsed : ByteBufUtil.hexDump(content));
    }
}
