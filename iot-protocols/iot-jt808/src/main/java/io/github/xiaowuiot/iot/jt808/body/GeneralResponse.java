package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

/**
 * 通用应答：终端通用应答 0x0001、平台通用应答 0x8001 格式完全一样。
 * <pre>应答流水号 WORD | 应答 ID WORD | 结果 BYTE</pre>
 */
public class GeneralResponse implements Jt808Body {

    public static final int SUCCESS = 0;
    public static final int FAILURE = 1;
    public static final int MESSAGE_ERROR = 2;
    public static final int UNSUPPORTED = 3;
    public static final int ALARM_CONFIRMED = 4;

    private final int replySerialNo;
    private final int replyMsgId;
    private final int result;

    public GeneralResponse(int replySerialNo, int replyMsgId, int result) {
        this.replySerialNo = replySerialNo;
        this.replyMsgId = replyMsgId;
        this.result = result;
    }

    public static GeneralResponse decode(ByteBuf body, Jt808Header header) {
        return new GeneralResponse(body.readUnsignedShort(), body.readUnsignedShort(), body.readUnsignedByte());
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeShort(replySerialNo);
        out.writeShort(replyMsgId);
        out.writeByte(result);
    }

    public int getReplySerialNo() {
        return replySerialNo;
    }

    public int getReplyMsgId() {
        return replyMsgId;
    }

    public int getResult() {
        return result;
    }

    public boolean isSuccess() {
        return result == SUCCESS;
    }

    @Override
    public String toString() {
        return String.format("GeneralResponse{replySerialNo=%d, replyMsgId=0x%04X, result=%d}",
                replySerialNo, replyMsgId, result);
    }
}
