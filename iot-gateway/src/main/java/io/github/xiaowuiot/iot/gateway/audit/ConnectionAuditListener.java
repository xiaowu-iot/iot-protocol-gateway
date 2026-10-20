package io.github.xiaowuiot.iot.gateway.audit;

import io.github.xiaowuiot.protocol.codec.session.Session;
import io.github.xiaowuiot.protocol.codec.session.SessionListener;
import io.netty.channel.Channel;

/**
 * 把会话管理器的连接、鉴权、上下线、断开事件记进 {@link AuditLog}。
 */
public class ConnectionAuditListener implements SessionListener {

    private final AuditLog audit;

    public ConnectionAuditListener(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void onConnect(String protocol, Channel channel) {
        audit.connection(protocol, null, "CONNECT", "建立连接", true, remote(channel), null);
    }

    @Override
    public void onAuth(String protocol, Channel channel, String deviceId, String action, boolean success, String detail) {
        audit.connection(protocol, deviceId, "AUTH", action + (success ? "成功" : "失败"), success, remote(channel), detail);
    }

    @Override
    public void onOnline(Session session) {
        audit.connection(session.getProtocol(), session.getDeviceId(), "ONLINE", "上线", true, remote(session.getChannel()), null);
    }

    @Override
    public void onOffline(Session session, boolean replaced) {
        audit.connection(session.getProtocol(), session.getDeviceId(), "OFFLINE", replaced ? "在新连接上重新上线，旧连接下线" : "下线",
                true, remote(session.getChannel()), null);
    }

    @Override
    public void onDisconnect(String protocol, Channel channel, String deviceId, String reason) {
        audit.connection(protocol, deviceId, "DISCONNECT", "断开连接", true, remote(channel), reason);
    }

    private static String remote(Channel channel) {
        return channel == null || channel.remoteAddress() == null ? null : channel.remoteAddress().toString().replaceFirst("^/", "");
    }
}
