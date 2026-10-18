package io.github.xiaowuiot.iot.gateway.message;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一消息模型：不管设备走的是 808、212 还是私有协议，到了网关之后都变成这个样子，下游只认它。
 * <p>
 * 下游（业务系统、存储、告警）不需要知道协议细节，新接一种协议，下游一行代码都不用改。
 *
 * @param deviceId    设备号
 * @param protocol    jt808 / hj212 / slp
 * @param type        location / online / offline / alarm / ……
 * @param dataTime    数据本身的时间（设备上报的时间），可能为空
 * @param receiveTime 网关收到的时间
 * @param data        具体内容，字段由 type 决定
 */
public record DeviceMessage(String deviceId, String protocol, String type, LocalDateTime dataTime,
                            Instant receiveTime, Map<String, Object> data) {

    public static final String TYPE_ONLINE = "online";
    public static final String TYPE_OFFLINE = "offline";
    public static final String TYPE_LOCATION = "location";
    public static final String TYPE_ALARM = "alarm";

    public static DeviceMessage of(String deviceId, String protocol, String type, LocalDateTime dataTime) {
        return new DeviceMessage(deviceId, protocol, type, dataTime, Instant.now(), new LinkedHashMap<>());
    }

    public DeviceMessage put(String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
        return this;
    }
}
