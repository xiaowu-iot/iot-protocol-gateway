package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

/**
 * 位置信息查询应答 0x0201。
 * <pre>应答流水号 WORD | 位置信息汇报（和 0x0200 的消息体一样）</pre>
 */
public class LocationQueryResponse implements Jt808Body {

    private final int replySerialNo;
    private final Location location;

    public LocationQueryResponse(int replySerialNo, Location location) {
        this.replySerialNo = replySerialNo;
        this.location = location;
    }

    public static LocationQueryResponse decode(ByteBuf body, Jt808Header header) {
        int serial = body.readUnsignedShort();
        return new LocationQueryResponse(serial, Location.decode(body, header));
    }

    @Override
    public void encode(ByteBuf out, Jt808Header header) {
        out.writeShort(replySerialNo);
        location.encode(out, header);
    }

    public int getReplySerialNo() {
        return replySerialNo;
    }

    public Location getLocation() {
        return location;
    }

    @Override
    public String toString() {
        return "LocationQueryResponse{replySerialNo=" + replySerialNo + ", " + location + '}';
    }
}
