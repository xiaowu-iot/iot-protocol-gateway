package io.github.xiaowuiot.iot.slp;

/**
 * 一条 SLP 消息。帧结构见 PROTOCOL.md。
 * <p>
 * body 的类型由命令决定，比如 0x01 是 {@link io.github.xiaowuiot.iot.slp.body.Login}；
 * 没有数据的命令（心跳）body 为 null；不认识的命令 body 是原始字节。
 */
public class SlpMessage {

    public static final int VERSION_1 = 1;

    public static final int LOGIN = 0x01;
    public static final int HEARTBEAT = 0x02;
    public static final int DOOR_STATUS = 0x03;
    public static final int OPEN_DOOR = 0x10;
    public static final int OTA_NOTIFY = 0x20;
    public static final int OTA_CHUNK_REQUEST = 0x21;
    public static final int OTA_RESULT = 0x22;

    /**
     * 应答的命令 = 请求的命令 | 0x80
     */
    public static final int RESPONSE_BIT = 0x80;

    private final int version;
    private final int command;
    private final int serialNo;
    private final long deviceId;
    private final Object body;

    public SlpMessage(int command, int serialNo, long deviceId, Object body) {
        this(VERSION_1, command, serialNo, deviceId, body);
    }

    public SlpMessage(int version, int command, int serialNo, long deviceId, Object body) {
        this.version = version;
        this.command = command;
        this.serialNo = serialNo;
        this.deviceId = deviceId;
        this.body = body;
    }

    /**
     * 生成对这条消息的应答：命令加上 0x80，流水号不变
     */
    public SlpMessage response(Object body) {
        return new SlpMessage(version, command | RESPONSE_BIT, serialNo, deviceId, body);
    }

    public boolean isResponse() {
        return (command & RESPONSE_BIT) != 0;
    }

    /**
     * 应答对应的请求命令
     */
    public int requestCommand() {
        return command & ~RESPONSE_BIT;
    }

    public int getVersion() {
        return version;
    }

    public int getCommand() {
        return command;
    }

    public int getSerialNo() {
        return serialNo;
    }

    public long getDeviceId() {
        return deviceId;
    }

    @SuppressWarnings("unchecked")
    public <T> T getBody() {
        return (T) body;
    }

    @Override
    public String toString() {
        return String.format("SlpMessage{cmd=0x%02X, serial=%d, device=%d, body=%s}", command, serialNo, deviceId, body);
    }
}
