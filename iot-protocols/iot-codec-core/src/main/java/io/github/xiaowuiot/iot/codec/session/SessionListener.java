package io.github.xiaowuiot.iot.codec.session;

/**
 * 设备上下线事件。
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
}
