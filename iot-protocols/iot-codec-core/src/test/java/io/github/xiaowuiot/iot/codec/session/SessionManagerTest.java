package io.github.xiaowuiot.iot.codec.session;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionManagerTest {

    private final SessionManager manager = new SessionManager();
    private final List<String> events = new ArrayList<>();

    SessionManagerTest() {
        manager.addListener(new SessionListener() {
            @Override
            public void onOnline(Session session) {
                events.add("online");
            }

            @Override
            public void onOffline(Session session, boolean replaced) {
                events.add(replaced ? "replaced" : "offline");
            }
        });
    }

    @Test
    void onlineAndOffline() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.register("13800138000", "jt808", ch);
        assertEquals(1, manager.size());
        manager.unregister(ch);
        assertNull(manager.get("13800138000"));
        assertEquals("[online, offline]", events.toString());
    }

    @Test
    void reconnectClosesOldConnection() {
        EmbeddedChannel oldCh = new EmbeddedChannel();
        EmbeddedChannel newCh = new EmbeddedChannel();
        manager.register("13800138000", "jt808", oldCh);
        Session newSession = manager.register("13800138000", "jt808", newCh);

        assertFalse(oldCh.isActive());
        assertSame(newSession, manager.get("13800138000"));
        assertEquals("[online, replaced, online]", events.toString());
    }

    @Test
    void lateOfflineOfOldConnectionDoesNotRemoveNewSession() {
        EmbeddedChannel oldCh = new EmbeddedChannel();
        EmbeddedChannel newCh = new EmbeddedChannel();
        Session oldSession = manager.register("13800138000", "jt808", oldCh);
        Session newSession = manager.register("13800138000", "jt808", newCh);

        // 模拟旧连接的断开事件姗姗来迟：旧会话重新挂回旧连接上，再触发断开
        oldCh.attr(Session.KEY).set(oldSession);
        manager.unregister(oldCh);

        assertSame(newSession, manager.get("13800138000"));
    }

    @Test
    void serialNoWrapsAround() {
        Session s = new Session("1", "jt808", new EmbeddedChannel());
        int last = -1;
        for (int i = 0; i < 65537; i++) {
            last = s.nextSerialNo();
        }
        assertEquals(0, last);
    }
}
