package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 终端控制 0x8105。
 * <pre>命令字 BYTE | 命令参数 STRING（各参数用半角分号分隔，可以没有）</pre>
 */
public class TerminalControl implements Jt808Body {

    public static final int RESET = 4;
    public static final int FACTORY_RESET = 5;
    public static final int CLOSE_CONNECTION = 6;
    public static final int CLOSE_WIRELESS = 7;

    private final int command;
    private final String params;

    public TerminalControl(int command) {
        this(command, null);
    }

    public TerminalControl(int command, String params) {
        this.command = command;
        this.params = params;
    }

    public static TerminalControl decode(ByteBuf body, Jt808Header header) {
        int cmd = body.readUnsignedByte();
        String p = body.isReadable() ? ByteBufUtils.readRemainingString(body, GBK) : null;
        return new TerminalControl(cmd, p);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeByte(command);
        if (params != null) {
            out.writeCharSequence(params, GBK);
        }
    }

    public int getCommand() {
        return command;
    }

    public String getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "TerminalControl{command=" + command + ", params=" + params + '}';
    }
}
