package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.codec.util.ByteBufUtils;
import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import static io.github.xiaowuiot.iot.codec.util.ByteBufUtils.GBK;

/**
 * 终端鉴权 0x0102。
 * <pre>
 * 2013：鉴权码 STRING（整个消息体）
 * 2019：鉴权码长度 BYTE | 鉴权码 STRING | IMEI BYTE[15] | 软件版本号 BYTE[20]
 * </pre>
 */
public class Auth implements Jt808Body {

    private final String authCode;
    private final String imei;
    private final String softwareVersion;

    public Auth(String authCode) {
        this(authCode, null, null);
    }

    public Auth(String authCode, String imei, String softwareVersion) {
        this.authCode = authCode;
        this.imei = imei;
        this.softwareVersion = softwareVersion;
    }

    public static Auth decode(ByteBuf body, Jt808Header header) {
        if (!header.isV2019()) {
            return new Auth(ByteBufUtils.readRemainingString(body, GBK));
        }
        String code = ByteBufUtils.readByteLengthString(body, GBK);
        String imei = ByteBufUtils.readString(body, 15, GBK);
        String version = ByteBufUtils.readString(body, 20, GBK);
        return new Auth(code, imei, version);
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        if (!header.isV2019()) {
            out.writeCharSequence(authCode, GBK);
            return;
        }
        ByteBufUtils.writeByteLengthString(out, authCode, GBK);
        ByteBufUtils.writeString(out, imei, 15, GBK);
        ByteBufUtils.writeString(out, softwareVersion, 20, GBK);
    }

    public String getAuthCode() {
        return authCode;
    }

    public String getImei() {
        return imei;
    }

    public String getSoftwareVersion() {
        return softwareVersion;
    }

    @Override
    public String toString() {
        return "Auth{authCode=" + authCode
                + (imei == null ? "" : ", imei=" + imei + ", softwareVersion=" + softwareVersion) + '}';
    }
}
