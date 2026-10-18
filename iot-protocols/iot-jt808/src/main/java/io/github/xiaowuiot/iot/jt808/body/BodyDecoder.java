package io.github.xiaowuiot.iot.jt808.body;

import io.github.xiaowuiot.iot.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

/**
 * 消息体解码。传进来的 body 只包含消息体部分，解码时可以放心读到末尾。
 */
@FunctionalInterface
public interface BodyDecoder<T> {

    T decode(ByteBuf body, Jt808Header header);
}
