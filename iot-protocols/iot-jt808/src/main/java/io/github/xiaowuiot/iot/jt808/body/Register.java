package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 终端注册 0x0100。
 * <pre>
 * 省域 ID WORD | 市县域 ID WORD | 制造商 ID BYTE[5/11] | 终端型号 BYTE[20/30] | 终端 ID BYTE[7/30]
 * | 车牌颜色 BYTE | 车牌号 STRING（剩余全部字节）
 * </pre>
 * 斜杠前是 2013 版的长度，斜杠后是 2019 版。
 */
public class Register implements Jt808Body {

    private int province;
    private int city;
    private String makerId;
    private String model;
    private String terminalId;
    private int plateColor;
    private String plate;

    public static Register decode(ByteBuf body, Jt808Header header) {
        boolean v19 = header.isV2019();
        Register r = new Register();
        r.province = body.readUnsignedShort();
        r.city = body.readUnsignedShort();
        r.makerId = ByteBufUtils.readString(body, v19 ? 11 : 5, GBK);
        r.model = ByteBufUtils.readString(body, v19 ? 30 : 20, GBK);
        r.terminalId = ByteBufUtils.readString(body, v19 ? 30 : 7, GBK);
        r.plateColor = body.readUnsignedByte();
        r.plate = ByteBufUtils.readRemainingString(body, GBK);
        return r;
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        boolean v19 = header.isV2019();
        out.writeShort(province);
        out.writeShort(city);
        ByteBufUtils.writeString(out, makerId, v19 ? 11 : 5, GBK);
        ByteBufUtils.writeString(out, model, v19 ? 30 : 20, GBK);
        ByteBufUtils.writeString(out, terminalId, v19 ? 30 : 7, GBK);
        out.writeByte(plateColor);
        if (plate != null) {
            out.writeCharSequence(plate, GBK);
        }
    }

    public int getProvince() {
        return province;
    }

    public Register setProvince(int province) {
        this.province = province;
        return this;
    }

    public int getCity() {
        return city;
    }

    public Register setCity(int city) {
        this.city = city;
        return this;
    }

    public String getMakerId() {
        return makerId;
    }

    public Register setMakerId(String makerId) {
        this.makerId = makerId;
        return this;
    }

    public String getModel() {
        return model;
    }

    public Register setModel(String model) {
        this.model = model;
        return this;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public Register setTerminalId(String terminalId) {
        this.terminalId = terminalId;
        return this;
    }

    public int getPlateColor() {
        return plateColor;
    }

    public Register setPlateColor(int plateColor) {
        this.plateColor = plateColor;
        return this;
    }

    public String getPlate() {
        return plate;
    }

    public Register setPlate(String plate) {
        this.plate = plate;
        return this;
    }

    @Override
    public String toString() {
        return "Register{province=" + province + ", city=" + city + ", makerId=" + makerId + ", model=" + model
                + ", terminalId=" + terminalId + ", plateColor=" + plateColor + ", plate=" + plate + '}';
    }
}
