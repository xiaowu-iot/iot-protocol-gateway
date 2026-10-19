package io.github.xiaowuiot.iot.codec.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 设备号 → 会话。
 * <p>
 * 两个容易出错的地方，这里都处理了：
 * <ol>
 *     <li><b>重连时旧连接还没断</b>：设备网络切换后很快重连，旧连接要等心跳超时才会断开。
 *     新会话注册时，主动关闭旧连接</li>
 *     <li><b>旧连接的断开事件晚到</b>：如果断开时直接按设备号删除，删掉的是新会话，设备明明在线却显示离线。
 *     所以删除时用 {@code remove(key, value)}，只删属于自己的那个会话</li>
 * </ol>
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 设备鉴权成功后调用。
     */
    public Session register(String deviceId, String protocol, Channel channel) {
        Session session = new Session(deviceId, protocol, channel);
        channel.attr(Session.KEY).set(session);
        Session old = sessions.put(deviceId, session);
        if (old != null && old.getChannel() != channel) {
            log.info("设备 {} 重新上线，关闭旧连接 {}", deviceId, old.getChannel().remoteAddress());
            old.getChannel().attr(Session.KEY).set(null);
            old.close();
            fireOffline(old, true);
        }
        if (old == null || old.getChannel() != channel) {
            log.info("设备上线 {}", session);
            fireOnline(session);
        }
        return session;
    }

    /**
     * 连接断开时调用。
     */
    public void unregister(Channel channel) {
        Session session = channel.attr(Session.KEY).getAndSet(null);
        if (session == null) {
            return;
        }
        if (sessions.remove(session.getDeviceId(), session)) {
            log.info("设备离线 {}", session);
            fireOffline(session, false);
        }
    }

    /** 关闭连接前记下原因，断开事件里带出去 */
    private static final AttributeKey<String> CLOSE_REASON = AttributeKey.valueOf("iot.closeReason");

    /**
     * 新连接建立时调用
     */
    public void connected(String protocol, Channel channel) {
        for (SessionListener l : listeners) {
            try {
                l.onConnect(protocol, channel);
            } catch (Exception e) {
                log.warn("连接事件处理失败 {}", channel, e);
            }
        }
    }

    /**
     * 注册、鉴权、登录完成时调用，成功失败都要调
     */
    public void authResult(String protocol, Channel channel, String deviceId, String action, boolean success, String detail) {
        for (SessionListener l : listeners) {
            try {
                l.onAuth(protocol, channel, deviceId, action, success, detail);
            } catch (Exception e) {
                log.warn("鉴权事件处理失败 {}", channel, e);
            }
        }
    }

    /**
     * 主动关闭连接前调用，记下原因，比如心跳超时
     */
    public static void closeReason(Channel channel, String reason) {
        channel.attr(CLOSE_REASON).setIfAbsent(reason);
    }

    /**
     * 连接断开时调用：有会话的先下线，再发断开事件
     */
    public void disconnected(String protocol, Channel channel) {
        Session session = get(channel);
        String deviceId = session == null ? null : session.getDeviceId();
        unregister(channel);
        String reason = channel.attr(CLOSE_REASON).get();
        for (SessionListener l : listeners) {
            try {
                l.onDisconnect(protocol, channel, deviceId, reason == null ? "连接断开" : reason);
            } catch (Exception e) {
                log.warn("断开事件处理失败 {}", channel, e);
            }
        }
    }

    public Session get(String deviceId) {
        return sessions.get(deviceId);
    }

    public static Session get(Channel channel) {
        return channel.attr(Session.KEY).get();
    }

    public Collection<Session> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    /**
     * 关闭所有连接，停机时用。
     */
    public void closeAll() {
        closeAll(null);
    }

    /**
     * 关闭某个协议的所有连接。多个协议共用一个会话管理器时，停掉 808 服务不能把 212 的连接也关了。
     */
    public void closeAll(String protocol) {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (protocol == null || protocol.equals(s.getProtocol())) {
                s.close();
            }
        }
    }

    private void fireOnline(Session session) {
        for (SessionListener l : listeners) {
            try {
                l.onOnline(session);
            } catch (Exception e) {
                log.warn("上线事件处理失败 {}", session, e);
            }
        }
    }

    private void fireOffline(Session session, boolean replaced) {
        for (SessionListener l : listeners) {
            try {
                l.onOffline(session, replaced);
            } catch (Exception e) {
                log.warn("离线事件处理失败 {}", session, e);
            }
        }
    }
}
