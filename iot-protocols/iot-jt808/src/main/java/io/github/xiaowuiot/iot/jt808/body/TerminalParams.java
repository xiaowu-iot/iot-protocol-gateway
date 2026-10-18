package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 终端参数：设置终端参数 0x8103、查询终端参数应答 0x0104 共用。
 * <pre>
 * 0x8103：                参数总数 BYTE | 参数项列表
 * 0x0104：应答流水号 WORD | 参数个数 BYTE | 参数项列表
 * 参数项：参数 ID DWORD | 参数长度 BYTE | 参数值
 * </pre>
 * 参数值的类型由参数 ID 决定。这里只登记了字符串和 BYTE、WORD 类型的常用参数，没登记的按 DWORD 处理，
 * 因为 808 里绝大多数参数都是 DWORD。
 */
public class TerminalParams implements Jt808Body {

    public static final long HEARTBEAT_INTERVAL = 0x0001;
    public static final long TCP_RESPONSE_TIMEOUT = 0x0002;
    public static final long TCP_RETRANSMIT_TIMES = 0x0003;
    public static final long MAIN_SERVER_ADDRESS = 0x0013;
    public static final long SERVER_TCP_PORT = 0x0018;
    public static final long DEFAULT_REPORT_INTERVAL = 0x0029;
    public static final long MAX_SPEED = 0x0055;
    public static final long MILEAGE = 0x0080;
    public static final long PLATE = 0x0083;
    public static final long PLATE_COLOR = 0x0084;

    /**
     * 用户自定义参数的范围：0xF000 ~ 0xFFFF
     */
    public static final long CUSTOM_START = 0xF000;
    public static final long CUSTOM_END = 0xFFFF;

    public enum Type {
        BYTE, WORD, DWORD, STRING
    }

    private static final Set<Long> STRING_PARAMS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> WORD_PARAMS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> BYTE_PARAMS = ConcurrentHashMap.newKeySet();

    static {
        STRING_PARAMS.addAll(Arrays.asList(0x0010L, 0x0011L, 0x0012L, 0x0013L, 0x0014L, 0x0015L, 0x0016L, 0x0017L,
                0x001AL, 0x001DL, 0x0040L, 0x0041L, 0x0042L, 0x0043L, 0x0044L, 0x0048L, 0x0049L, PLATE));
        WORD_PARAMS.addAll(Arrays.asList(0x0031L, 0x005BL, 0x005CL, 0x005DL, 0x005EL, 0x0081L, 0x0082L, 0x0101L,
                0x0103L));
        BYTE_PARAMS.addAll(Arrays.asList(PLATE_COLOR, 0x0090L, 0x0091L, 0x0092L, 0x0094L));
    }

    /**
     * 登记参数的类型，主要用于自定义参数（0xF000 ~ 0xFFFF）。
     * <p>
     * 必须登记的原因：编码时要知道写几个字节；解析时 1、2、4 字节都能按长度猜出来，
     * 但字符串参数长度不固定，不登记的话长度恰好是 1、2、4 的字符串会被当成数字。
     */
    public static void registerType(long id, Type type) {
        STRING_PARAMS.remove(id);
        WORD_PARAMS.remove(id);
        BYTE_PARAMS.remove(id);
        switch (type) {
            case STRING:
                STRING_PARAMS.add(id);
                break;
            case WORD:
                WORD_PARAMS.add(id);
                break;
            case BYTE:
                BYTE_PARAMS.add(id);
                break;
            default:
                // DWORD 是默认类型，不需要登记
        }
    }

    /**
     * 只有 0x0104 才有
     */
    private final int replySerialNo;
    private final Map<Long, Object> params;

    public TerminalParams(Map<Long, Object> params) {
        this(-1, params);
    }

    public TerminalParams(int replySerialNo, Map<Long, Object> params) {
        this.replySerialNo = replySerialNo;
        this.params = params;
    }

    public static TerminalParams decodeSet(ByteBuf body, Jt808Header header) {
        return new TerminalParams(readParams(body));
    }

    public static TerminalParams decodeQueryResponse(ByteBuf body, Jt808Header header) {
        int serial = body.readUnsignedShort();
        return new TerminalParams(serial, readParams(body));
    }

    private static Map<Long, Object> readParams(ByteBuf body) {
        int count = body.readUnsignedByte();
        Map<Long, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < count && body.readableBytes() >= 5; i++) {
            long id = body.readUnsignedInt();
            int len = body.readUnsignedByte();
            params.put(id, readValue(id, body.readSlice(len)));
        }
        return params;
    }

    private static Object readValue(long id, ByteBuf value) {
        if (STRING_PARAMS.contains(id)) {
            return value.toString(GBK);
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
        if (replySerialNo >= 0) {
            out.writeShort(replySerialNo);
        }
        out.writeByte(params.size());
        for (Map.Entry<Long, Object> e : params.entrySet()) {
            long id = e.getKey();
            Object v = e.getValue();
            out.writeInt((int) id);
            if (v instanceof String) {
                byte[] bytes = ((String) v).getBytes(GBK);
                out.writeByte(bytes.length).writeBytes(bytes);
            } else if (v instanceof byte[]) {
                out.writeByte(((byte[]) v).length).writeBytes((byte[]) v);
            } else if (BYTE_PARAMS.contains(id)) {
                out.writeByte(1).writeByte(((Number) v).intValue());
            } else if (WORD_PARAMS.contains(id)) {
                out.writeByte(2).writeShort(((Number) v).intValue());
            } else {
                out.writeByte(4).writeInt((int) ((Number) v).longValue());
            }
        }
    }

    public int getReplySerialNo() {
        return replySerialNo;
    }

    public Map<Long, Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TerminalParams{");
        if (replySerialNo >= 0) {
            sb.append("replySerialNo=").append(replySerialNo).append(", ");
        }
        boolean first = true;
        for (Map.Entry<Long, Object> e : params.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            Object v = e.getValue();
            sb.append(String.format("0x%04X=", e.getKey()))
                    .append(v instanceof byte[] ? ByteBufUtil.hexDump((byte[]) v) : v);
        }
        return sb.append('}').toString();
    }
}
