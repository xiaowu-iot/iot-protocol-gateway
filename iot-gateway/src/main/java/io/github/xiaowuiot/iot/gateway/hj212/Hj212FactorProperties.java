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
 * <p>
 * HJ212-2025 加密传输的设备，按 MN 配置通信密钥（16 个字符，和下发给设备的 NewSK 一样）。
 * 密钥更新的过渡期可以配多个，逗号分隔，新的在前：
 * <pre>
 * iot:
 *   hj212:
 *     secret-keys:
 *       "[010000A8900016F000169DC0]": 新密钥16个字符,旧密钥16个字符
 * </pre>
 * 密钥不要提交到代码仓库，用环境变量或外部配置文件覆盖。
 */
@ConfigurationProperties(prefix = "iot.hj212")
public class Hj212FactorProperties {

    private Map<String, String> customFactors = new LinkedHashMap<>();

    private Map<String, String> secretKeys = new LinkedHashMap<>();

    public Map<String, String> getSecretKeys() {
        return secretKeys;
    }

    public void setSecretKeys(Map<String, String> secretKeys) {
        this.secretKeys = secretKeys;
    }

    public Map<String, String> getCustomFactors() {
        return customFactors;
    }

    public void setCustomFactors(Map<String, String> customFactors) {
        this.customFactors = customFactors;
    }
}
