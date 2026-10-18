package io.github.xiaowuiot.iot.codec.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一台设备的一条连接。
 * <p>
 * 设备鉴权成功后才创建会话，此后平台通过设备号找到会话，再通过会话里的 {@link Channel} 给设备下发数据。
 * 同一台设备重连时会创建新的会话，旧会话随旧连接一起作废。
 */
public class Session {

    /**
     * 挂在 Channel 上，连接断开时能直接找到对应的会话
     */
    public static final AttributeKey<Session> KEY = AttributeKey.valueOf("iot.session");

    private final String deviceId;
    private final String protocol;
    private final Channel channel;
    private final long createTime = System.currentTimeMillis();
    private volatile long lastActiveTime = createTime;

    /**
     * 平台下发消息用的流水号，每台设备独立计数
     */
    private final AtomicInteger serialNo = new AtomicInteger();

    /**
     * 协议相关的附加信息，比如 808 的协议版本
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public Session(String deviceId, String protocol, Channel channel) {
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.channel = channel;
    }

    /**
     * 取下一个流水号，范围 0~65535，用完后从 0 重新开始。
     */
    public int nextSerialNo() {
        return serialNo.getAndIncrement() & 0xFFFF;
    }

    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public ChannelFuture write(Object msg) {
        return channel.writeAndFlush(msg);
    }

    public void close() {
        channel.close();
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name) {
        return (T) attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getProtocol() {
        return protocol;
    }

    public Channel getChannel() {
        return channel;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    @Override
    public String toString() {
        return protocol + ":" + deviceId + channel.remoteAddress();
    }
}
