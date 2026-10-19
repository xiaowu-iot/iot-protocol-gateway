package io.github.xiaowuiot.iot.codec.session;

import io.netty.channel.Channel;

/**
 * 设备连接和上下线事件。
 * <p>
 * 回调在 Netty 的 IO 线程里执行，不要在这里做耗时操作，需要写库、发消息的话交给其他线程。
 */
public interface SessionListener {

    default void onOnline(Session session) {
    }

    /**
     * @param replaced true 表示是因为设备在别的连接上重新上线，旧会话被挤掉，并不是真的离线
     */
    default void onOffline(Session session, boolean replaced) {
    }

    /**
     * 新的 TCP 连接建立，这时还不知道是哪台设备
     */
    default void onConnect(String protocol, Channel channel) {
    }

    /**
     * 注册、鉴权、登录的结果。212 没有鉴权这一步，按数据里的密码校验结果算
     *
     * @param action 注册 / 鉴权 / 登录 / 密码校验
     * @param detail 失败原因或结果码
     */
    default void onAuth(String protocol, Channel channel, String deviceId, String action, boolean success, String detail) {
    }

    /**
     * TCP 连接断开
     *
     * @param deviceId 连接上已经鉴权的设备号，没鉴权就断开的是 null
     * @param reason   断开原因：连接断开、心跳超时、连接异常……
     */
    default void onDisconnect(String protocol, Channel channel, String deviceId, String reason) {
    }
}
