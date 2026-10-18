package io.github.xiaowuiot.iot.jt808;

import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.jt808.codec.Jt808Codecs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试工具：用 EmbeddedChannel 装配完整的 808 编解码流水线，输入输出都是十六进制字符串。
 */
public final class Jt808TestSupport {

    private Jt808TestSupport() {
    }

    public static EmbeddedChannel channel(ChannelHandler... handlers) {
        return new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
                Jt808Codecs.addTo(ch.pipeline());
                ch.pipeline().addLast(handlers);
            }
        });
    }

    public static void writeHex(EmbeddedChannel ch, String hex) {
        ch.writeInbound(Unpooled.wrappedBuffer(HexUtils.fromHex(hex)));
    }

    public static List<Jt808Message> readMessages(EmbeddedChannel ch) {
        List<Jt808Message> list = new ArrayList<>();
        Object o;
        while ((o = ch.readInbound()) != null) {
            list.add((Jt808Message) o);
        }
        return list;
    }

    /**
     * 取出所有发出的字节，转成十六进制
     */
    public static List<String> readOutboundHex(EmbeddedChannel ch) {
        List<String> list = new ArrayList<>();
        ByteBuf buf;
        while ((buf = ch.readOutbound()) != null) {
            list.add(HexUtils.toHex(buf));
            buf.release();
        }
        return list;
    }

    /**
     * 编码一条消息，返回线路上的十六进制
     */
    public static String encodeHex(Jt808Message msg) {
        EmbeddedChannel ch = channel();
        ch.writeOutbound(msg);
        List<String> out = readOutboundHex(ch);
        ch.finishAndReleaseAll();
        return String.join("", out);
    }
}
