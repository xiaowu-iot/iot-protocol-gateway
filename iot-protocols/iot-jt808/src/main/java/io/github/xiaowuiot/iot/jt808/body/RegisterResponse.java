package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 终端注册应答 0x8100。
 * <pre>应答流水号 WORD | 结果 BYTE | 鉴权码 STRING（只有成功时才有）</pre>
 */
public class RegisterResponse implements Jt808Body {

    public static final int SUCCESS = 0;
    public static final int VEHICLE_REGISTERED = 1;
    public static final int VEHICLE_NOT_FOUND = 2;
    public static final int TERMINAL_REGISTERED = 3;
    public static final int TERMINAL_NOT_FOUND = 4;

    private final int replySerialNo;
    private final int result;
    private final String authCode;

    public RegisterResponse(int replySerialNo, int result, String authCode) {
        this.replySerialNo = replySerialNo;
        this.result = result;
        this.authCode = authCode;
    }

    public static RegisterResponse decode(ByteBuf body, Jt808Header header) {
        int serial = body.readUnsignedShort();
        int result = body.readUnsignedByte();
        String code = result == SUCCESS ? ByteBufUtils.readRemainingString(body, GBK) : null;
        return new RegisterResponse(serial, result, code);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeShort(replySerialNo);
        out.writeByte(result);
        if (result == SUCCESS && authCode != null) {
            out.writeCharSequence(authCode, GBK);
        }
    }

    public int getReplySerialNo() {
        return replySerialNo;
    }

    public int getResult() {
        return result;
    }

    public String getAuthCode() {
        return authCode;
    }

    @Override
    public String toString() {
        return "RegisterResponse{replySerialNo=" + replySerialNo + ", result=" + result + ", authCode=" + authCode + '}';
    }
}
