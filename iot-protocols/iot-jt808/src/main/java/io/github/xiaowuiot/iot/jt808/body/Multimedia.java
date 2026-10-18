package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多媒体相关的消息：拍照指令、多媒体事件、多媒体数据上传及应答。
 * <pre>
 * 平台 ── 0x8801 摄像头立即拍摄 ──► 终端
 * 平台 ◄── 0x0805 拍摄应答（带多媒体 ID）── 终端
 * 平台 ◄── 0x0800 多媒体事件信息（可选）── 终端
 * 平台 ◄── 0x0801 多媒体数据上传（图片一般几十 KB，分包）── 终端
 * 平台 ── 0x8800 多媒体数据上传应答（缺包时带上要重传的包序号）──► 终端
 * </pre>
 */
public final class Multimedia {

    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_AUDIO = 1;
    public static final int TYPE_VIDEO = 2;

    public static final int FORMAT_JPEG = 0;

    /**
     * 事件项编码：0 平台下发指令，1 定时动作，2 抢劫报警触发，3 碰撞侧翻报警触发
     */
    public static final int EVENT_PLATFORM_COMMAND = 0;

    private Multimedia() {
    }

    /**
     * 摄像头立即拍摄命令 0x8801。
     * <pre>
     * 通道 ID BYTE | 拍摄命令 WORD（0 停止拍摄，0xFFFF 录像，其他为拍照张数）| 拍照间隔 / 录像时间 WORD（秒）
     * | 保存标志 BYTE（1 保存在终端，0 实时上传）| 分辨率 BYTE | 图像质量 BYTE（1~10，1 最好）
     * | 亮度 BYTE | 对比度 BYTE | 饱和度 BYTE | 色度 BYTE（0~255）
     * </pre>
     */
    public static final class CameraCommand implements Jt808Body {
        private final int channel;
        private final int command;
        private final int interval;
        private final int save;
        private final int resolution;
        private final int quality;
        private final int[] picture;

        public CameraCommand(int channel, int photos, int interval) {
            this(channel, photos, interval, 0, 1, 5, new int[]{128, 64, 64, 128});
        }

        public CameraCommand(int channel, int command, int interval, int save, int resolution, int quality, int[] picture) {
            this.channel = channel;
            this.command = command;
            this.interval = interval;
            this.save = save;
            this.resolution = resolution;
            this.quality = quality;
            this.picture = picture;
        }

        public static CameraCommand decode(ByteBuf b, Jt808Header h) {
            return new CameraCommand(b.readUnsignedByte(), b.readUnsignedShort(), b.readUnsignedShort(),
                    b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    new int[]{b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte()});
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeByte(channel).writeShort(command).writeShort(interval).writeByte(save)
                    .writeByte(resolution).writeByte(quality);
            for (int p : picture) {
                out.writeByte(p);
            }
        }

        public int getChannel() {
            return channel;
        }

        /**
         * 0 停止，0xFFFF 录像，其他为拍照张数
         */
        public int getCommand() {
            return command;
        }

        public int getInterval() {
            return interval;
        }

        public boolean isSave() {
            return save == 1;
        }

        @Override
        public String toString() {
            return "CameraCommand{channel=" + channel + ", command=" + command + ", interval=" + interval
                    + ", save=" + save + ", resolution=" + resolution + ", quality=" + quality + '}';
        }
    }

    /**
     * 摄像头立即拍摄命令应答 0x0805。
     * <pre>应答流水号 WORD | 结果 BYTE（0 成功，1 失败，2 通道不支持）| 多媒体 ID 个数 WORD | 多媒体 ID DWORD × 个数</pre>
     * 结果为成功时才有后面的字段
     */
    public static final class CameraResponse implements Jt808Body {
        public static final int SUCCESS = 0;
        public static final int FAILURE = 1;
        public static final int CHANNEL_UNSUPPORTED = 2;

        private final int replySerialNo;
        private final int result;
        private final List<Long> mediaIds;

        public CameraResponse(int replySerialNo, int result, List<Long> mediaIds) {
            this.replySerialNo = replySerialNo;
            this.result = result;
            this.mediaIds = mediaIds;
        }

        public static CameraResponse decode(ByteBuf b, Jt808Header h) {
            int serial = b.readUnsignedShort();
            int result = b.readUnsignedByte();
            List<Long> ids = new ArrayList<>();
            if (result == SUCCESS && b.isReadable(2)) {
                int n = b.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    ids.add(b.readUnsignedInt());
                }
            }
            return new CameraResponse(serial, result, ids);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeShort(replySerialNo).writeByte(result);
            if (result == SUCCESS) {
                out.writeShort(mediaIds.size());
                for (Long id : mediaIds) {
                    out.writeInt((int) id.longValue());
                }
            }
        }

        public int getReplySerialNo() {
            return replySerialNo;
        }

        public int getResult() {
            return result;
        }

        public List<Long> getMediaIds() {
            return Collections.unmodifiableList(mediaIds);
        }

        @Override
        public String toString() {
            return "CameraResponse{replySerialNo=" + replySerialNo + ", result=" + result + ", mediaIds=" + mediaIds + '}';
        }
    }

    /**
     * 多媒体事件信息上传 0x0800：多媒体 ID DWORD | 类型 BYTE | 格式 BYTE | 事件项编码 BYTE | 通道 ID BYTE
     */
    public static final class Event implements Jt808Body {
        private final long mediaId;
        private final int type;
        private final int format;
        private final int event;
        private final int channel;

        public Event(long mediaId, int type, int format, int event, int channel) {
            this.mediaId = mediaId;
            this.type = type;
            this.format = format;
            this.event = event;
            this.channel = channel;
        }

        public static Event decode(ByteBuf b, Jt808Header h) {
            return new Event(b.readUnsignedInt(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedByte());
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeInt((int) mediaId).writeByte(type).writeByte(format).writeByte(event).writeByte(channel);
        }

        public long getMediaId() {
            return mediaId;
        }

        @Override
        public String toString() {
            return "MultimediaEvent{id=" + mediaId + ", type=" + type + ", format=" + format + ", event=" + event
                    + ", channel=" + channel + '}';
        }
    }

    /**
     * 多媒体数据上传 0x0801。
     * <pre>
     * 多媒体 ID DWORD | 类型 BYTE | 格式 BYTE | 事件项编码 BYTE | 通道 ID BYTE
     * | 位置基本信息 BYTE[28]（0x0200 的前 28 字节，拍摄时的位置）| 多媒体数据包
     * </pre>
     * 整个消息体通常远超 1023 字节，按 808 的分包规则发送，平台收齐后合并成一条再解析。
     */
    public static final class Data implements Jt808Body {
        private final long mediaId;
        private final int type;
        private final int format;
        private final int event;
        private final int channel;
        private final Location location;
        private final byte[] data;

        public Data(long mediaId, int type, int format, int event, int channel, Location location, byte[] data) {
            this.mediaId = mediaId;
            this.type = type;
            this.format = format;
            this.event = event;
            this.channel = channel;
            this.location = location;
            this.data = data;
        }

        public static Data decode(ByteBuf b, Jt808Header h) {
            long id = b.readUnsignedInt();
            int type = b.readUnsignedByte();
            int format = b.readUnsignedByte();
            int event = b.readUnsignedByte();
            int channel = b.readUnsignedByte();
            // 只有 28 字节的基本信息，没有附加信息：切出来单独解析，免得把图片数据当成附加信息
            Location loc = Location.decode(b.readSlice(28), h);
            return new Data(id, type, format, event, channel, loc, ByteBufUtil.getBytes(b));
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeInt((int) mediaId).writeByte(type).writeByte(format).writeByte(event).writeByte(channel);
            // 只写 28 字节的基本信息，不写附加信息
            Location basic = new Location().setAlarm(location.getAlarm()).setStatus(location.getStatus())
                    .setLatitude(location.getLatitude()).setLongitude(location.getLongitude())
                    .setAltitude(location.getAltitude()).setSpeed(location.getSpeed())
                    .setDirection(location.getDirection()).setTime(location.getTime());
            basic.encode(out, h);
            out.writeBytes(data);
        }

        public long getMediaId() {
            return mediaId;
        }

        public int getType() {
            return type;
        }

        public int getFormat() {
            return format;
        }

        public int getEvent() {
            return event;
        }

        public int getChannel() {
            return channel;
        }

        public Location getLocation() {
            return location;
        }

        public byte[] getData() {
            return data;
        }

        @Override
        public String toString() {
            return "MultimediaData{id=" + mediaId + ", type=" + type + ", format=" + format + ", event=" + event
                    + ", channel=" + channel + ", size=" + data.length + '}';
        }
    }

    /**
     * 多媒体数据上传应答 0x8800：多媒体 ID DWORD | 重传包总数 BYTE | 重传包序号 WORD × 总数。
     * 全部收到时重传包总数为 0
     */
    public static final class DataResponse implements Jt808Body {
        private final long mediaId;
        private final List<Integer> retransmit;

        public DataResponse(long mediaId, List<Integer> retransmit) {
            this.mediaId = mediaId;
            this.retransmit = retransmit;
        }

        public static DataResponse decode(ByteBuf b, Jt808Header h) {
            long id = b.readUnsignedInt();
            List<Integer> list = new ArrayList<>();
            if (b.isReadable()) {
                int n = b.readUnsignedByte();
                for (int i = 0; i < n; i++) {
                    list.add(b.readUnsignedShort());
                }
            }
            return new DataResponse(id, list);
        }

        @Override
        public void encode(ByteBuf out, Jt808Header h) {
            out.writeInt((int) mediaId).writeByte(retransmit.size());
            for (Integer no : retransmit) {
                out.writeShort(no);
            }
        }

        public long getMediaId() {
            return mediaId;
        }

        public List<Integer> getRetransmit() {
            return Collections.unmodifiableList(retransmit);
        }

        @Override
        public String toString() {
            return "MultimediaResponse{id=" + mediaId + ", retransmit=" + retransmit + '}';
        }
    }
}
