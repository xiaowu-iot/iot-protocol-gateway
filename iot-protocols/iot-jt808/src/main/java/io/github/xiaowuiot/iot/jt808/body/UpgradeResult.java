package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

/**
 * 终端升级结果通知 0x0108。
 * <pre>升级类型 BYTE | 升级结果 BYTE（0 成功，1 失败，2 取消）</pre>
 */
public class UpgradeResult implements Jt808Body {

    public static final int SUCCESS = 0;
    public static final int FAILURE = 1;
    public static final int CANCELLED = 2;

    private final int type;
    private final int result;

    public UpgradeResult(int type, int result) {
        this.type = type;
        this.result = result;
    }

    public static UpgradeResult decode(ByteBuf body, Jt808Header header) {
        return new UpgradeResult(body.readUnsignedByte(), body.readUnsignedByte());
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeByte(type);
        out.writeByte(result);
    }

    public int getType() {
        return type;
    }

    public int getResult() {
        return result;
    }

    @Override
    public String toString() {
        return "UpgradeResult{type=" + type + ", result=" + result + '}';
    }
}
