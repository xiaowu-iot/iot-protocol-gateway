package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.codec.frame.EscapedDelimiterFrameDecoder;
import io.github.xiaowuiot.iot.codec.frame.EscapedDelimiterFrameEncoder;
import io.github.xiaowuiot.iot.codec.util.ByteEscaper;
import io.netty.channel.ChannelPipeline;

/**
 * 808 编解码器的装配。平台和模拟器（终端）用的是同一套，因为 808 的报文格式是双向对称的。
 */
public final class Jt808Codecs {

    public static final byte DELIMITER = 0x7E;

    /**
     * 0x7D → 7D 01，0x7E → 7D 02
     */
    public static final ByteEscaper ESCAPER = new ByteEscaper((byte) 0x7D,
            new byte[]{0x7D, 0x7E}, new byte[]{0x01, 0x02});

    /**
     * 线路上一帧的最大长度：分包的 2019 版消息头 21 + 消息体 1023 + 校验 1，全部转义后翻倍，再加首尾 7E
     */
    public static final int MAX_FRAME_LENGTH = (21 + 1023 + 1) * 2 + 2;

    private static final Jt808MessageEncoder MESSAGE_ENCODER = new Jt808MessageEncoder();
    private static final EscapedDelimiterFrameEncoder FRAME_ENCODER = new EscapedDelimiterFrameEncoder(DELIMITER, ESCAPER);

    private Jt808Codecs() {
    }

    /**
     * 装配到流水线上。
     * <pre>
     * 入站：字节 → 切帧并还原转义 → Jt808Message
     * 出站：Jt808Message → 消息头 + 消息体 + 校验 → 转义并加首尾 7E → 字节
     * </pre>
     * 出站是从流水线末尾往前走的，所以帧编码器要加在消息编码器前面。
     */
    public static void addTo(ChannelPipeline pipeline) {
        pipeline.addLast("jt808FrameDecoder", new EscapedDelimiterFrameDecoder(DELIMITER, ESCAPER, MAX_FRAME_LENGTH))
                .addLast("jt808FrameEncoder", FRAME_ENCODER)
                .addLast("jt808MessageDecoder", new Jt808MessageDecoder())
                .addLast("jt808MessageEncoder", MESSAGE_ENCODER);
    }
}
