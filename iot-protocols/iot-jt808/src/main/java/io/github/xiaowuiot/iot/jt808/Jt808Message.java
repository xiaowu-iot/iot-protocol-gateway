package io.github.xiaowuiot.iot.jt808;

/**
 * 一条 808 消息：消息头 + 已解析的消息体。
 * <p>
 * 消息体的类型由消息 ID 决定，比如 0x0200 是 {@link io.github.xiaowuiot.iot.jt808.body.Location}；
 * 心跳这类没有消息体的消息，body 为 null；还没支持的消息，body 是原始字节 {@code byte[]}。
 */
public class Jt808Message {

    private final Jt808Header header;
    private final Object body;

    /**
     * 分包消息还没收齐时为 true，此时 body 为 null，只需要回一个通用应答
     */
    private final boolean fragment;

    public Jt808Message(Jt808Header header, Object body) {
        this(header, body, false);
    }

    private Jt808Message(Jt808Header header, Object body, boolean fragment) {
        this.header = header;
        this.body = body;
        this.fragment = fragment;
    }

    public static Jt808Message fragment(Jt808Header header) {
        return new Jt808Message(header, null, true);
    }

    public Jt808Header getHeader() {
        return header;
    }

    public int getMsgId() {
        return header.getMsgId();
    }

    @SuppressWarnings("unchecked")
    public <T> T getBody() {
        return (T) body;
    }

    public boolean isFragment() {
        return fragment;
    }

    @Override
    public String toString() {
        return header + (fragment ? " (分包未收齐)" : " " + body);
    }
}
