package io.github.xiaowuiot.iot.codec.frame;

/**
 * 帧解码器丢弃数据的原因。
 */
public enum DiscardReason {

    GARBAGE("帧起始标记之前的无效数据"),
    TOO_LONG("超过最大帧长度"),
    BAD_LENGTH("长度字段不合法"),
    BAD_ESCAPE("非法转义"),
    BAD_TRAILER("帧尾不匹配"),
    BAD_CHECKSUM("校验失败");

    private final String description;

    DiscardReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
