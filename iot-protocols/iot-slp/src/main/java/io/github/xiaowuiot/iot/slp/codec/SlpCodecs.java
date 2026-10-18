package io.github.xiaowuiot.iot.slp.codec;

import io.github.xiaowuiot.iot.codec.frame.AbstractFrameDecoder;
import io.github.xiaowuiot.iot.codec.frame.DiscardReason;
import io.github.xiaowuiot.iot.codec.stats.CodecStats;
import io.github.xiaowuiot.iot.codec.util.Checksums;
import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.slp.SlpMessage;
import io.github.xiaowuiot.iot.slp.body.SlpBodies;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * SLP 编解码。
 * <pre>
 * AA 55 | 版本 | 命令 | 流水号(2) | 设备 ID(8) | 长度(2) | 数据 | CRC16(2)
 * </pre>
 */
public final class SlpCodecs {

    private static final Logger log = LoggerFactory.getLogger(SlpCodecs.class);

    public static final byte[] HEADER = {(byte) 0xAA, 0x55};
    public static final int HEADER_LENGTH = 16;
    public static final int MAX_FRAME_LENGTH = 8192;

    private static final Map<Integer, Function<ByteBuf, Object>> DECODERS = new HashMap<>();

    static {
        DECODERS.put(SlpMessage.LOGIN, SlpBodies.Login::decode);
        DECODERS.put(SlpMessage.LOGIN | 0x80, SlpBodies.LoginAck::decode);
        DECODERS.put(SlpMessage.HEARTBEAT, b -> null);
        DECODERS.put(SlpMessage.HEARTBEAT | 0x80, b -> null);
        DECODERS.put(SlpMessage.DOOR_STATUS, SlpBodies.DoorStatus::decode);
        DECODERS.put(SlpMessage.DOOR_STATUS | 0x80, SlpBodies.Result::decode);
        DECODERS.put(SlpMessage.OPEN_DOOR, SlpBodies.OpenDoor::decode);
        DECODERS.put(SlpMessage.OPEN_DOOR | 0x80, SlpBodies.Result::decode);
        DECODERS.put(SlpMessage.OTA_NOTIFY, SlpBodies.OtaNotify::decode);
        DECODERS.put(SlpMessage.OTA_NOTIFY | 0x80, SlpBodies.Result::decode);
        DECODERS.put(SlpMessage.OTA_CHUNK_REQUEST, SlpBodies.OtaChunkRequest::decode);
        DECODERS.put(SlpMessage.OTA_CHUNK_REQUEST | 0x80, SlpBodies.OtaChunk::decode);
        DECODERS.put(SlpMessage.OTA_RESULT, SlpBodies.OtaResult::decode);
        DECODERS.put(SlpMessage.OTA_RESULT | 0x80, SlpBodies.Result::decode);
    }

    private static final Encoder ENCODER = new Encoder();
    private static final Decoder DECODER = new Decoder();

    private SlpCodecs() {
    }

    public static void addTo(ChannelPipeline pipeline) {
        pipeline.addLast("slpFrameDecoder", new FrameDecoder())
                .addLast("slpDecoder", DECODER)
                .addLast("slpEncoder", ENCODER);
    }

    /**
     * 切帧，<b>同时校验 CRC</b>。
     * <p>
     * 为什么不像 808 那样切帧和校验分两层：808 有首尾 7E，切错了最多丢一帧。
     * "帧头 + 长度"的协议没有帧尾，如果数据里碰巧出现 AA 55，后面两个字节又被当成了一个合理的长度，
     * 切出来的就是一段错位的数据。这时候如果先切完再校验，校验失败后整段丢掉，<b>藏在里面的真帧也跟着丢了</b>。
     * 在切帧时校验，失败了只跳过 1 个字节重新找帧头，真帧就能被找回来。
     */
    public static class FrameDecoder extends AbstractFrameDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!skipToMarker(ctx, in, HEADER)) {
                return;
            }
            int start = in.readerIndex();
            if (in.readableBytes() < HEADER_LENGTH) {
                return;
            }
            int dataLength = in.getUnsignedShort(start + 14);
            int frameLength = HEADER_LENGTH + dataLength + 2;
            if (frameLength > MAX_FRAME_LENGTH) {
                discard(ctx, in, 1, DiscardReason.BAD_LENGTH);
                return;
            }
            if (in.readableBytes() < frameLength) {
                return;
            }
            int expected = in.getUnsignedShort(start + frameLength - 2);
            int actual = Checksums.crc16Modbus(in, start + 2, frameLength - 4);
            if (expected != actual) {
                discard(ctx, in, 1, DiscardReason.BAD_CHECKSUM);
                return;
            }
            out.add(in.readRetainedSlice(frameLength));
        }
    }

    @ChannelHandler.Sharable
    public static class Decoder extends MessageToMessageDecoder<ByteBuf> {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
            int start = frame.readerIndex();
            frame.skipBytes(2);
            int version = frame.readUnsignedByte();
            int command = frame.readUnsignedByte();
            int serial = frame.readUnsignedShort();
            long deviceId = frame.readLong();
            int length = frame.readUnsignedShort();
            if (version != SlpMessage.VERSION_1) {
                // 版本不认识也往上交，由业务层回"版本不支持"
                log.warn("{} 不支持的协议版本 {}", ctx.channel(), version);
            }
            ByteBuf data = frame.readSlice(length);
            Function<ByteBuf, Object> decoder = DECODERS.get(command);
            try {
                Object body = decoder == null ? ByteBufUtil.getBytes(data) : decoder.apply(data);
                out.add(new SlpMessage(version, command, serial, deviceId, body));
            } catch (RuntimeException e) {
                CodecStats.increment("slp.body");
                log.warn("{} 数据解析失败：{}", ctx.channel(), HexUtils.toHex(frame.readerIndex(start)), e);
            }
        }
    }

    @ChannelHandler.Sharable
    public static class Encoder extends MessageToByteEncoder<SlpMessage> {

        @Override
        protected void encode(ChannelHandlerContext ctx, SlpMessage msg, ByteBuf out) {
            encode(msg, out);
        }

        public static void encode(SlpMessage msg, ByteBuf out) {
            int start = out.writerIndex();
            out.writeBytes(HEADER);
            out.writeByte(msg.getVersion());
            out.writeByte(msg.getCommand());
            out.writeShort(msg.getSerialNo());
            out.writeLong(msg.getDeviceId());
            int lengthIndex = out.writerIndex();
            out.writeShort(0);
            Object body = msg.getBody();
            if (body instanceof SlpBodies.Body) {
                ((SlpBodies.Body) body).encode(out);
            } else if (body instanceof byte[]) {
                out.writeBytes((byte[]) body);
            }
            int dataLength = out.writerIndex() - lengthIndex - 2;
            out.setShort(lengthIndex, dataLength);
            out.writeShort(Checksums.crc16Modbus(out, start + 2, out.writerIndex() - start - 2));
        }
    }
}
