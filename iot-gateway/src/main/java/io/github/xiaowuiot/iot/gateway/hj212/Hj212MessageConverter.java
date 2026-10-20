package io.github.xiaowuiot.iot.gateway.hj212;

import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.github.xiaowuiot.protocol.hj212.Hj212Commands;
import io.github.xiaowuiot.protocol.hj212.Hj212Factors;
import io.github.xiaowuiot.protocol.hj212.Hj212Packet;
import io.github.xiaowuiot.protocol.hj212.Hj212Versions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 212 数据包 → 统一消息模型。
 * <p>
 * 212 的值在报文里全是字符串，这里转成数字；数据标记（N 正常、F 停运、M 维护、D 故障、T 超限……）原样保留，
 * 下游统计均值时要排除非 N 的数据，这是环保业务的硬性规定。
 */
public final class Hj212MessageConverter {

    public static final String PROTOCOL = "hj212";

    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Hj212MessageConverter() {
    }

    public static DeviceMessage convert(Hj212Packet received) {
        // 2005 版的因子编码统一换成 2017 版，下游只认一套编码；原始编码保留在 originalCode 里
        String version = Hj212Versions.detect(received);
        Map<String, String> originalCodes = new HashMap<>();
        Hj212Packet p = Hj212Versions.normalize(received, originalCodes);
        DeviceMessage m = DeviceMessage.of(p.getMn(), PROTOCOL, type(p.getCn()), dataTime(p.getParam("DataTime")))
                .put("cn", p.getCn())
                .put("st", p.getSt())
                .put("qn", p.getQn())
                .put("protocolVersion", version);
        if (p.isRequested()) {
            // 平台主动提取的历史数据：下游入库时按 DataTime 覆盖，不能当成新数据触发实时报警
            m.put("requested", true);
        }
        if (received.isRefill()) {
            // 2025 版 RF=1：现场机补传的历史数据，和 requested 一样不该触发实时报警
            m.put("refill", true);
        }
        if (received.isEncrypted()) {
            m.put("encrypted", true);
        }
        Map<String, Object> factors = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> f : p.getFactors().entrySet()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : f.getValue().entrySet()) {
                fields.put(lowerFirst(field.getKey()), number(field.getValue()));
            }
            Hj212Factors.Factor def = Hj212Factors.get(f.getKey());
            if (def != null) {
                fields.put("name", def.name);
                if (!def.unit.isEmpty()) {
                    fields.put("unit", def.unit);
                }
            } else {
                // 没登记的编码：不丢弃，原样输出，同时列出来，提醒去和设备厂家、平台方确认含义
                unknown.add(f.getKey());
            }
            if (originalCodes.containsKey(f.getKey())) {
                fields.put("originalCode", originalCodes.get(f.getKey()));
            }
            String flag = f.getValue().get("Flag");
            if (flag != null) {
                // 停运、维护、故障、校准、通讯异常时的数据不能参与均值计算和超标判断
                fields.put("valid", Hj212Commands.isValidFlag(flag));
                // B 在 2017 版是通讯异常，2025 版是闷炉
                fields.put("flagName", Hj212Commands.flagName(flag, received.version()));
            }
            factors.put(f.getKey(), fields);
        }
        if (!factors.isEmpty()) {
            m.put("factors", factors);
        }
        if (!unknown.isEmpty()) {
            m.put("unknownFactors", unknown);
        }
        for (Map.Entry<String, String> e : p.getParams().entrySet()) {
            if (!"DataTime".equals(e.getKey())) {
                m.put(lowerFirst(e.getKey()), number(e.getValue()));
            }
        }
        return m;
    }

    static String type(String cn) {
        return switch (cn) {
            case Hj212Packet.CN_REALTIME -> "env_realtime";
            case Hj212Packet.CN_MINUTE -> "env_minute";
            case Hj212Packet.CN_HOUR -> "env_hour";
            case Hj212Packet.CN_DAY -> "env_day";
            default -> "hj212_" + cn;
        };
    }

    private static LocalDateTime dataTime(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, DATA_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 能转成数字的转成数字，转不了的（比如数据标记 N）保留字符串
     */
    private static Object number(String v) {
        if (v == null || v.isEmpty()) {
            return v;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
