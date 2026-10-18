package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.BcdUtils;
import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 查询终端属性应答 0x0107（平台查询用 0x8107，消息体为空）。
 * <pre>
 * 终端类型 WORD | 制造商 ID BYTE[5/11] | 终端型号 BYTE[20/30] | 终端 ID BYTE[7/30] | SIM 卡 ICCID BCD[10]
 * | 硬件版本号长度 BYTE | 硬件版本号 STRING | 固件版本号长度 BYTE | 固件版本号 STRING
 * | GNSS 模块属性 BYTE | 通信模块属性 BYTE
 * </pre>
 * 注意：这条应答<b>没有应答流水号</b>，平台没法按流水号匹配，只能按"等的是哪种应答"匹配。
 */
public class TerminalAttributes implements Jt808Body {

    private int terminalType;
    private String makerId;
    private String model;
    private String terminalId;
    private String iccid;
    private String hardwareVersion;
    private String firmwareVersion;
    private int gnssAttr;
    private int commAttr;

    public static TerminalAttributes decode(ByteBuf body, Jt808Header header) {
        boolean v19 = header.isV2019();
        TerminalAttributes a = new TerminalAttributes();
        a.terminalType = body.readUnsignedShort();
        a.makerId = ByteBufUtils.readString(body, v19 ? 11 : 5, GBK);
        a.model = ByteBufUtils.readString(body, v19 ? 30 : 20, GBK);
        a.terminalId = ByteBufUtils.readString(body, v19 ? 30 : 7, GBK);
        a.iccid = BcdUtils.readBcd(body, 10);
        a.hardwareVersion = ByteBufUtils.readByteLengthString(body, GBK);
        a.firmwareVersion = ByteBufUtils.readByteLengthString(body, GBK);
        a.gnssAttr = body.readUnsignedByte();
        a.commAttr = body.readUnsignedByte();
        return a;
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        boolean v19 = header.isV2019();
        out.writeShort(terminalType);
        ByteBufUtils.writeString(out, makerId, v19 ? 11 : 5, GBK);
        ByteBufUtils.writeString(out, model, v19 ? 30 : 20, GBK);
        ByteBufUtils.writeString(out, terminalId, v19 ? 30 : 7, GBK);
        BcdUtils.writeBcd(out, iccid, 10);
        ByteBufUtils.writeByteLengthString(out, hardwareVersion, GBK);
        ByteBufUtils.writeByteLengthString(out, firmwareVersion, GBK);
        out.writeByte(gnssAttr);
        out.writeByte(commAttr);
    }

    /**
     * 终端类型 bit0 适用客运车辆，bit1 危险品车辆，bit2 普通货运车辆，bit3 出租车辆，bit6 支持硬盘录像，bit7 一体机
     */
    public int getTerminalType() {
        return terminalType;
    }

    public TerminalAttributes setTerminalType(int terminalType) {
        this.terminalType = terminalType;
        return this;
    }

    public String getMakerId() {
        return makerId;
    }

    public TerminalAttributes setMakerId(String makerId) {
        this.makerId = makerId;
        return this;
    }

    public String getModel() {
        return model;
    }

    public TerminalAttributes setModel(String model) {
        this.model = model;
        return this;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public TerminalAttributes setTerminalId(String terminalId) {
        this.terminalId = terminalId;
        return this;
    }

    public String getIccid() {
        return iccid;
    }

    public TerminalAttributes setIccid(String iccid) {
        this.iccid = iccid;
        return this;
    }

    public String getHardwareVersion() {
        return hardwareVersion;
    }

    public TerminalAttributes setHardwareVersion(String hardwareVersion) {
        this.hardwareVersion = hardwareVersion;
        return this;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public TerminalAttributes setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
        return this;
    }

    /**
     * GNSS 模块属性：bit0 GPS，bit1 北斗，bit2 GLONASS，bit3 Galileo
     */
    public int getGnssAttr() {
        return gnssAttr;
    }

    public TerminalAttributes setGnssAttr(int gnssAttr) {
        this.gnssAttr = gnssAttr;
        return this;
    }

    /**
     * 通信模块属性：bit0 GPRS，bit1 CDMA，bit2 TD-SCDMA，bit3 WCDMA，bit4 CDMA2000，bit5 TD-LTE，bit7 其他
     */
    public int getCommAttr() {
        return commAttr;
    }

    public TerminalAttributes setCommAttr(int commAttr) {
        this.commAttr = commAttr;
        return this;
    }

    @Override
    public String toString() {
        return "TerminalAttributes{type=" + terminalType + ", maker=" + makerId + ", model=" + model
                + ", terminalId=" + terminalId + ", iccid=" + iccid + ", hw=" + hardwareVersion
                + ", fw=" + firmwareVersion + ", gnss=" + gnssAttr + ", comm=" + commAttr + '}';
    }
}
