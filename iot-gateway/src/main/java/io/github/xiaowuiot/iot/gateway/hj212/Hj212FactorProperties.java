package io.github.xiaowuiot.iot.gateway.hj212;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 和平台方约定好的自定义因子：
 * <pre>
 * iot:
 *   hj212:
 *     custom-factors:
 *       w99001: 雨水排口液位|m|2
 * </pre>
 * 值是"名称|单位|小数位数"。编码和标准冲突时启动失败。
 */
@ConfigurationProperties(prefix = "iot.hj212")
public class Hj212FactorProperties {

    private Map<String, String> customFactors = new LinkedHashMap<>();

    public Map<String, String> getCustomFactors() {
        return customFactors;
    }

    public void setCustomFactors(Map<String, String> customFactors) {
        this.customFactors = customFactors;
    }
}
