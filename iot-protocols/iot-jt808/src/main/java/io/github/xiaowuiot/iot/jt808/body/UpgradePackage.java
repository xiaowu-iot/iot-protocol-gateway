package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 下发终端升级包 0x8108。
 * <pre>
 * 升级类型 BYTE | 制造商 ID BYTE[5/11] | 版本号长度 BYTE | 版本号 STRING | 升级数据包长度 DWORD | 升级数据包
 * </pre>
 * 固件一般有几百 KB，远超 1023 字节的消息体上限，所以这条消息总是分包发送：
 * 先把整个消息体编码出来，再按包切开，见 {@link io.github.xiaowuiot.iot.jt808.codec.Jt808Subpackages}。
 */
public class UpgradePackage implements Jt808Body {

    public static final int TYPE_TERMINAL = 0;
    public static final int TYPE_IC_CARD_READER = 12;
    public static final int TYPE_GNSS = 52;

    private final int type;
    private final String makerId;
    private final String version;
    private final byte[] data;

    public UpgradePackage(int type, String makerId, String version, byte[] data) {
        this.type = type;
        this.makerId = makerId;
        this.version = version;
        this.data = data;
    }

    public static UpgradePackage decode(ByteBuf body, Jt808Header header) {
        int type = body.readUnsignedByte();
        String maker = ByteBufUtils.readString(body, header.isV2019() ? 11 : 5, GBK);
        String version = ByteBufUtils.readByteLengthString(body, GBK);
        long len = body.readUnsignedInt();
        byte[] data = ByteBufUtil.getBytes(body.readSlice((int) len));
        return new UpgradePackage(type, maker, version, data);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeByte(type);
        ByteBufUtils.writeString(out, makerId, header.isV2019() ? 11 : 5, GBK);
        ByteBufUtils.writeByteLengthString(out, version, GBK);
        out.writeInt(data.length);
        out.writeBytes(data);
    }

    public int getType() {
        return type;
    }

    public String getMakerId() {
        return makerId;
    }

    public String getVersion() {
        return version;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public String toString() {
        return "UpgradePackage{type=" + type + ", makerId=" + makerId + ", version=" + version
                + ", size=" + data.length + '}';
    }
}
