package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 信息类、车辆控制类的几条消息：事件设置与报告、提问与应答、车门控制。
 */
public final class InfoBodies {

    private InfoBodies() {
    }

    /**
     * 0x8301 事件设置：平台把"预设事件"下发到终端，司机在屏幕上点一下就能上报（比如"堵车""事故""车辆故障"）。
     * <pre>
     * 设置类型 BYTE（0 删除全部 1 更新 2 追加 3 修改 4 删除指定） | 设置总数 BYTE |
     * [事件 ID BYTE | 事件内容长度 BYTE | 事件内容 STRING] × 设置总数
     * </pre>
     * 设置类型为 0 时，后面什么都没有；为 4 时，每一项只有事件 ID 有意义，内容长度填 0。
     */
    public static final class EventSetting implements Jt808Body {
        public static final int DELETE_ALL = 0;
        public static final int UPDATE = 1;
        public static final int APPEND = 2;
        public static final int MODIFY = 3;
        public static final int DELETE = 4;

        public final int type;
        public final Map<Integer, String> events;

        public EventSetting(int type, Map<Integer, String> events) {
            this.type = type;
            this.events = events;
        }

        public static EventSetting decode(ByteBuf b, Jt808Header h) {
            int type = b.readUnsignedByte();
            Map<Integer, String> events = new LinkedHashMap<>();
            if (type != DELETE_ALL && b.isReadable()) {
                int n = b.readUnsignedByte();
                for (int i = 0; i < n; i++) {
                    int id = b.readUnsignedByte();
                    events.put(id, ByteBufUtils.readByteLengthString(b, GBK));
                }
            }
            return new EventSetting(type, events);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(type);
            if (type == DELETE_ALL) {
                return;
            }
            out.writeByte(events.size());
            for (Map.Entry<Integer, String> e : events.entrySet()) {
                out.writeByte(e.getKey());
                ByteBufUtils.writeByteLengthString(out, type == DELETE ? "" : e.getValue(), GBK);
            }
        }

        @Override
        public String toString() {
            return "EventSetting{type=" + type + ", events=" + events + '}';
        }
    }

    /**
     * 0x0301 事件报告：事件 ID BYTE。平台回通用应答
     */
    public static final class EventReport implements Jt808Body {
        public final int eventId;

        public EventReport(int eventId) {
            this.eventId = eventId;
        }

        public static EventReport decode(ByteBuf b, Jt808Header h) {
            return new EventReport(b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(eventId);
        }

        public int getEventId() {
            return eventId;
        }

        @Override
        public String toString() {
            return "EventReport{" + eventId + '}';
        }
    }

    /**
     * 0x8302 提问下发：一个问题加几个候选答案，司机在屏幕上选一个。
     * <pre>
     * 标志 BYTE（bit0 紧急，bit3 终端 TTS 播读，bit4 广告屏显示） | 问题内容长度 BYTE | 问题文本 STRING |
     * [答案 ID BYTE | 答案内容长度 WORD | 答案内容 STRING] × N
     * </pre>
     * 注意答案内容长度是 WORD，问题内容长度却是 BYTE：同一条消息里两个长度字段的宽度不一样。
     * 终端选好后用 0x0302 应答，不是通用应答。
     */
    public static final class Question implements Jt808Body {
        public static final int URGENT = 1;
        public static final int TTS = 1 << 3;

        public final int flag;
        public final String question;
        public final Map<Integer, String> answers;

        public Question(int flag, String question, Map<Integer, String> answers) {
            this.flag = flag;
            this.question = question;
            this.answers = answers;
        }

        public static Question decode(ByteBuf b, Jt808Header h) {
            int flag = b.readUnsignedByte();
            String q = ByteBufUtils.readByteLengthString(b, GBK);
            Map<Integer, String> answers = new LinkedHashMap<>();
            while (b.isReadable()) {
                int id = b.readUnsignedByte();
                int len = b.readUnsignedShort();
                answers.put(id, ByteBufUtils.readString(b, len, GBK));
            }
            return new Question(flag, q, answers);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(flag);
            ByteBufUtils.writeByteLengthString(out, question, GBK);
            for (Map.Entry<Integer, String> e : answers.entrySet()) {
                out.writeByte(e.getKey());
                byte[] bytes = e.getValue().getBytes(GBK);
                out.writeShort(bytes.length);
                out.writeBytes(bytes);
            }
        }

        @Override
        public String toString() {
            return "Question{" + question + ", answers=" + answers + '}';
        }
    }

    /**
     * 0x0302 提问应答：应答流水号 WORD | 答案 ID BYTE
     */
    public static final class QuestionAnswer implements Jt808Body {
        public final int replySerialNo;
        public final int answerId;

        public QuestionAnswer(int replySerialNo, int answerId) {
            this.replySerialNo = replySerialNo;
            this.answerId = answerId;
        }

        public static QuestionAnswer decode(ByteBuf b, Jt808Header h) {
            return new QuestionAnswer(b.readUnsignedShort(), b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo);
            out.writeByte(answerId);
        }

        public int getReplySerialNo() {
            return replySerialNo;
        }

        public int getAnswerId() {
            return answerId;
        }

        @Override
        public String toString() {
            return "QuestionAnswer{reply=" + replySerialNo + ", answer=" + answerId + '}';
        }
    }

    /**
     * 0x8500 车辆控制（2013 版）：控制标志 BYTE，bit0 0 车门解锁 1 车门加锁。
     * <p>
     * 2019 版改成了"控制类型数量 + 控制类型列表"的通用结构，可以扩展更多控制项，这里只实现 2013 版。
     */
    public static final class VehicleControl implements Jt808Body {
        public static final int LOCK_DOOR = 1;

        public final int flag;

        public VehicleControl(int flag) {
            this.flag = flag;
        }

        public static VehicleControl decode(ByteBuf b, Jt808Header h) {
            return new VehicleControl(b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(flag);
        }

        public boolean isLock() {
            return (flag & LOCK_DOOR) != 0;
        }

        @Override
        public String toString() {
            return "VehicleControl{" + (isLock() ? "车门加锁" : "车门解锁") + '}';
        }
    }

    /**
     * 0x0500 车辆控制应答：应答流水号 WORD | 位置信息汇报消息体。
     * <p>
     * 应答里带一整条位置：平台从状态位 bit12（车门加锁）确认控制是否真的生效，而不只是"终端收到了"。
     */
    public static final class VehicleControlResponse implements Jt808Body {
        public final int replySerialNo;
        public final Location location;

        public VehicleControlResponse(int replySerialNo, Location location) {
            this.replySerialNo = replySerialNo;
            this.location = location;
        }

        public static VehicleControlResponse decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            return new VehicleControlResponse(serial, Location.decode(b, h));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo);
            location.encode(out, h);
        }

        public int getReplySerialNo() {
            return replySerialNo;
        }

        @Override
        public String toString() {
            return "VehicleControlResponse{reply=" + replySerialNo + ", " + location + '}';
        }
    }
}
