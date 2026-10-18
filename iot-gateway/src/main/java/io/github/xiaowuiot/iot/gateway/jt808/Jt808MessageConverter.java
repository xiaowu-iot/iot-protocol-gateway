package io.github.xiaowuiot.iot.gateway.jt808;

import io.github.xiaowuiot.iot.codec.util.HexUtils;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import io.github.xiaowuiot.iot.jt808.Jt808Flags;
import io.github.xiaowuiot.iot.jt808.Jt808Message;
import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.AlarmExtras;
import io.github.xiaowuiot.iot.jt808.body.AreaAlarmInfo;
import io.github.xiaowuiot.iot.jt808.body.DriverInfo;
import io.github.xiaowuiot.iot.jt808.body.InfoBodies;
import io.github.xiaowuiot.iot.jt808.body.Location;
import io.github.xiaowuiot.iot.jt808.body.LocationBatch;
import io.github.xiaowuiot.iot.jt808.body.Multimedia;
import io.github.xiaowuiot.iot.jt808.body.PassThrough;
import io.github.xiaowuiot.iot.jt808.body.Recorder;
import io.github.xiaowuiot.iot.jt808.body.UpgradeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 808 消息 → 统一消息模型。协议细节（报警位、状态位、附加信息 ID）在这里翻译成下游看得懂的字段。
 */
public final class Jt808MessageConverter {

    public static final String PROTOCOL = "jt808";

    private Jt808MessageConverter() {
    }

    public static List<DeviceMessage> convert(String deviceId, Jt808Message msg) {
        List<DeviceMessage> list = new ArrayList<>(1);
        switch (msg.getMsgId()) {
            case MessageId.LOCATION -> {
                Location l = msg.getBody();
                list.add(location(deviceId, l, false));
                // 有报警位的，额外发一条报警消息：报警走单独的 topic，量小、要求实时
                if (l.getAlarm() != 0) {
                    list.add(DeviceMessage.of(deviceId, PROTOCOL, DeviceMessage.TYPE_ALARM, l.getTime())
                            .put("alarm", l.getAlarm())
                            .put("alarms", Jt808Flags.alarmNames(l.getAlarm()))
                            .put("needConfirm", (l.getAlarm() & Jt808Flags.CONFIRM_REQUIRED) != 0)
                            .put("serialNo", msg.getHeader().getSerialNo())
                            .put("lat", l.getLatitude())
                            .put("lng", l.getLongitude())
                            .put("speed", l.getSpeed())
                            .put("overspeed", l.getExtras().get(AlarmExtras.Overspeed.ID))
                            .put("areaAlarm", l.getExtras().get(AreaAlarmInfo.ID))
                            .put("segmentTime", l.getExtras().get(AlarmExtras.SegmentTime.ID)));
                }
            }
            case MessageId.LOCATION_BATCH -> {
                LocationBatch batch = msg.getBody();
                for (Location l : batch.getLocations()) {
                    list.add(location(deviceId, l, batch.isBlindArea()));
                }
            }
            case MessageId.UPGRADE_RESULT -> {
                UpgradeResult r = msg.getBody();
                list.add(DeviceMessage.of(deviceId, PROTOCOL, "upgrade_result", null)
                        .put("upgradeType", r.getType())
                        .put("result", r.getResult()));
            }
            case MessageId.PASS_THROUGH_UP -> list.add(passThrough(deviceId, msg.getBody()));
            case MessageId.MULTIMEDIA_DATA -> {
                if (msg.getBody() instanceof Multimedia.Data d) {
                    Location l = d.getLocation();
                    list.add(DeviceMessage.of(deviceId, PROTOCOL, "multimedia", l.getTime())
                            .put("mediaId", d.getMediaId())
                            .put("mediaType", d.getType())
                            .put("format", d.getFormat())
                            .put("event", d.getEvent())
                            .put("channel", d.getChannel())
                            .put("size", d.getData().length)
                            .put("lat", l.getLatitude())
                            .put("lng", l.getLongitude()));
                } else {
                    list.add(raw(deviceId, msg));
                }
            }
            case MessageId.EVENT_REPORT -> list.add(DeviceMessage.of(deviceId, PROTOCOL, "event_report", null)
                    .put("eventId", msg.<InfoBodies.EventReport>getBody().getEventId()));
            case MessageId.QUESTION_ANSWER -> {
                InfoBodies.QuestionAnswer a = msg.getBody();
                // 应答流水号对应平台下发 0x8302 时的流水号，下游靠它找到是哪个问题
                list.add(DeviceMessage.of(deviceId, PROTOCOL, "question_answer", null)
                        .put("questionSerialNo", a.getReplySerialNo())
                        .put("answerId", a.getAnswerId()));
            }
            case MessageId.DRIVER_INFO -> {
                DriverInfo d = msg.getBody();
                list.add(DeviceMessage.of(deviceId, PROTOCOL, "driver_info", d.getTime())
                        .put("onDuty", d.getStatus() == DriverInfo.CARD_IN)
                        .put("readResult", d.getStatus() == DriverInfo.CARD_IN ? d.getReadResult() : null)
                        .put("name", d.getName())
                        .put("certNo", d.getCertNo())
                        .put("authority", d.getAuthority())
                        .put("validUntil", d.getValidUntil() == null ? null : d.getValidUntil().toString()));
            }
            case MessageId.RECORDER_UPLOAD -> list.add(recorder(deviceId, msg.getBody()));
            case MessageId.VEHICLE_CONTROL_RESPONSE -> {
                Location l = msg.<InfoBodies.VehicleControlResponse>getBody().location;
                list.add(DeviceMessage.of(deviceId, PROTOCOL, "vehicle_control_result", l.getTime())
                        .put("doorLocked", (l.getStatus() & 1L << 12) != 0));
            }
            default -> list.add(raw(deviceId, msg));
        }
        return list;
    }

    /**
     * 透传：带上类型和原始字节交给下游
     */
    private static DeviceMessage passThrough(String deviceId, PassThrough p) {
        return DeviceMessage.of(deviceId, PROTOCOL, "pass_through", null)
                .put("passThroughType", p.getType())
                .put("raw", HexUtils.toHex(p.getContent()));
    }

    /**
     * 行驶记录仪：先解 GB/T 19056 这一层，解不开的保留原始字节
     */
    private static DeviceMessage recorder(String deviceId, Recorder.Upload up) {
        DeviceMessage m = DeviceMessage.of(deviceId, PROTOCOL, "recorder_data", null)
                .put("command", String.format("0x%02X", up.cmd));
        try {
            Map<String, Object> parsed = Recorder.parse(up.cmd, Recorder.unframe(up.block, up.cmd));
            if (parsed != null) {
                parsed.forEach(m::put);
                return m;
            }
        } catch (IllegalArgumentException e) {
            m.put("error", e.getMessage());
        }
        return m.put("raw", HexUtils.toHex(up.block));
    }

    /**
     * 网关还不认识的消息也往下游送，带上原始字节，方便以后补解析
     */
    private static DeviceMessage raw(String deviceId, Jt808Message msg) {
        Object body = msg.getBody();
        DeviceMessage m = DeviceMessage.of(deviceId, PROTOCOL, String.format("0x%04X", msg.getMsgId()), null);
        if (body instanceof byte[] bytes) {
            m.put("raw", HexUtils.toHex(bytes));
        } else if (body != null) {
            m.put("body", body.toString());
        }
        return m;
    }

    public static DeviceMessage location(String deviceId, Location l, boolean blindArea) {
        DeviceMessage m = DeviceMessage.of(deviceId, PROTOCOL, DeviceMessage.TYPE_LOCATION, l.getTime())
                .put("lat", l.getLatitude())
                .put("lng", l.getLongitude())
                .put("altitude", l.getAltitude())
                .put("speed", l.getSpeed())
                .put("direction", l.getDirection())
                .put("acc", l.isAccOn())
                .put("positioned", l.isPositioned())
                .put("alarm", l.getAlarm())
                .put("status", l.getStatus())
                .put("mileage", l.getMileageKm())
                .put("satellites", l.getSatellites());
        if (blindArea) {
            // 补传数据：下游不能用它触发实时报警
            m.put("blindArea", true);
        }
        for (Map.Entry<Integer, Object> e : l.getExtras().entrySet()) {
            if (e.getKey() != Location.EXTRA_MILEAGE && e.getKey() != Location.EXTRA_SATELLITES) {
                Object v = e.getValue();
                m.put(String.format("extra_%02X", e.getKey()),
                        v instanceof byte[] b ? HexUtils.toHex(b) : v);
            }
        }
        return m;
    }
}
