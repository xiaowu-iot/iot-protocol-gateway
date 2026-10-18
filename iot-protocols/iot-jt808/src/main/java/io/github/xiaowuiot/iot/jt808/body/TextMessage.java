package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 文本信息下发 0x8300。
 * <pre>
 * 2013：标志 BYTE |                 文本信息 STRING
 * 2019：标志 BYTE | 文本类型 BYTE | 文本信息 STRING
 * </pre>
 * 标志的常用位：bit0 紧急，bit2 终端显示器显示，bit3 终端 TTS 播读。
 */
public class TextMessage implements Jt808Body {

    public static final int FLAG_URGENT = 1;
    public static final int FLAG_DISPLAY = 1 << 2;
    public static final int FLAG_TTS = 1 << 3;

    private final int flag;
    private final int textType;
    private final String text;

    public TextMessage(int flag, String text) {
        this(flag, 1, text);
    }

    /**
     * @param textType 只有 2019 版有：1 通知，2 服务
     */
    public TextMessage(int flag, int textType, String text) {
        this.flag = flag;
        this.textType = textType;
        this.text = text;
    }

    public static TextMessage decode(ByteBuf body, Jt808Header header) {
        int flag = body.readUnsignedByte();
        int type = header.isV2019() ? body.readUnsignedByte() : 1;
        return new TextMessage(flag, type, ByteBufUtils.readRemainingString(body, GBK));
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeByte(flag);
        if (header.isV2019()) {
            out.writeByte(textType);
        }
        out.writeCharSequence(text, GBK);
    }

    public int getFlag() {
        return flag;
    }

    public int getTextType() {
        return textType;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "TextMessage{flag=" + flag + ", textType=" + textType + ", text=" + text + '}';
    }
}
