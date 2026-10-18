package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.body.Jt808Body;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 把超过 1023 字节的消息体切成多包。
 * <p>
 * 每一包都是一条完整的 808 消息，有自己的流水号；消息头里带上"总包数 + 包序号（从 1 开始）"。
 */
public final class Jt808Subpackages {

    /**
     * 每包消息体的默认大小。没有用满 1023，是因为部分终端的接收缓冲区按 1K 整数设计，留一点余量更稳
     */
    public static final int DEFAULT_PACKET_SIZE = 1000;

    private Jt808Subpackages() {
    }

    /**
     * @param template 消息 ID、版本、手机号从这里取
     * @param serials  每包取一个流水号，通常是 {@code session::nextSerialNo}
     */
    public static List<Jt808Message> split(Jt808Header template, Jt808Body body, int packetSize, IntSupplier serials) {
        ByteBuf buf = Unpooled.buffer();
        try {
            body.encode(buf, template);
            return split(template, ByteBufUtil.getBytes(buf), packetSize, serials);
        } finally {
            buf.release();
        }
    }

    public static List<Jt808Message> split(Jt808Header template, byte[] body, int packetSize, IntSupplier serials) {
        if (packetSize <= 0 || packetSize > Jt808Header.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("每包大小必须在 1~1023 之间：" + packetSize);
        }
        int total = Math.max(1, (body.length + packetSize - 1) / packetSize);
        if (total > 0xFFFF) {
            throw new IllegalArgumentException("消息体太大，超过 65535 包");
        }
        List<Jt808Message> list = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Jt808Header h = Jt808Header.to(template, template.getMsgId(), serials.getAsInt());
            if (total > 1) {
                h.setSubpackage(true);
                h.setTotalPackets(total);
                h.setPacketNo(i + 1);
            }
            int from = i * packetSize;
            list.add(new Jt808Message(h, Arrays.copyOfRange(body, from, Math.min(body.length, from + packetSize))));
        }
        return list;
    }
}
