package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 几条字段很少的平台下行消息。
 */
public final class SimpleBodies {

    private SimpleBodies() {
    }

    /**
     * 查询指定终端参数 0x8106：参数总数 BYTE | 参数 ID DWORD × 总数。终端用 0x0104 应答
     */
    public static final class QueryParamsById implements Jt808Body {
        private final List<Long> ids;

        public QueryParamsById(List<Long> ids) {
            this.ids = ids;
        }

        public static QueryParamsById decode(ByteBuf body, Jt808Header header) {
            int n = body.readUnsignedByte();
            List<Long> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ids.add(body.readUnsignedInt());
            }
            return new QueryParamsById(ids);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header header) {
            out.writeByte(ids.size());
            for (Long id : ids) {
                out.writeInt((int) id.longValue());
            }
        }

        public List<Long> getIds() {
            return Collections.unmodifiableList(ids);
        }

        @Override
        public String toString() {
            return "QueryParamsById" + ids;
        }
    }

    /**
     * 临时位置跟踪控制 0x8202：时间间隔 WORD（秒，0 表示停止跟踪）| 位置跟踪有效期 DWORD（秒）。
     * 终端在有效期内按这个间隔上报位置，到期后恢复原来的上报策略
     */
    public static final class TrackingControl implements Jt808Body {
        private final int intervalSeconds;
        private final long validSeconds;

        public TrackingControl(int intervalSeconds, long validSeconds) {
            this.intervalSeconds = intervalSeconds;
            this.validSeconds = validSeconds;
        }

        public static TrackingControl decode(ByteBuf body, Jt808Header header) {
            int interval = body.readUnsignedShort();
            long valid = body.isReadable(4) ? body.readUnsignedInt() : 0;
            return new TrackingControl(interval, valid);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header header) {
            out.writeShort(intervalSeconds);
            if (intervalSeconds != 0) {
                out.writeInt((int) validSeconds);
            }
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public long getValidSeconds() {
            return validSeconds;
        }

        @Override
        public String toString() {
            return "TrackingControl{interval=" + intervalSeconds + "s, valid=" + validSeconds + "s}";
        }
    }

    /**
     * 人工确认报警消息 0x8203：报警消息流水号 WORD（0 表示确认该类型的所有报警）| 人工确认报警类型 DWORD。
     * 确认类型的位和报警标志位对应，只有"需要确认才清零"的几位有效，见 Jt808Flags.CONFIRM_REQUIRED
     */
    public static final class AlarmConfirm implements Jt808Body {
        private final int alarmSerialNo;
        private final long types;

        public AlarmConfirm(int alarmSerialNo, long types) {
            this.alarmSerialNo = alarmSerialNo;
            this.types = types;
        }

        public static AlarmConfirm decode(ByteBuf body, Jt808Header header) {
            return new AlarmConfirm(body.readUnsignedShort(), body.readUnsignedInt());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header header) {
            out.writeShort(alarmSerialNo);
            out.writeInt((int) types);
        }

        public int getAlarmSerialNo() {
            return alarmSerialNo;
        }

        public long getTypes() {
            return types;
        }

        @Override
        public String toString() {
            return String.format("AlarmConfirm{serial=%d, types=0x%08X}", alarmSerialNo, types);
        }
    }
}
