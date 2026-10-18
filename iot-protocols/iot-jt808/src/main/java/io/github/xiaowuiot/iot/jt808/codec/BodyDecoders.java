package io.github.xiaowuiot.iot.jt808.codec;

import io.github.xiaowuiot.iot.jt808.MessageId;
import io.github.xiaowuiot.iot.jt808.body.Auth;
import io.github.xiaowuiot.iot.jt808.body.BodyDecoder;
import io.github.xiaowuiot.iot.jt808.body.CircleArea;
import io.github.xiaowuiot.iot.jt808.body.DriverInfo;
import io.github.xiaowuiot.iot.jt808.body.GeneralResponse;
import io.github.xiaowuiot.iot.jt808.body.InfoBodies;
import io.github.xiaowuiot.iot.jt808.body.Location;
import io.github.xiaowuiot.iot.jt808.body.LocationBatch;
import io.github.xiaowuiot.iot.jt808.body.LocationQueryResponse;
import io.github.xiaowuiot.iot.jt808.body.Multimedia;
import io.github.xiaowuiot.iot.jt808.body.PassThrough;
import io.github.xiaowuiot.iot.jt808.body.PolygonArea;
import io.github.xiaowuiot.iot.jt808.body.Recorder;
import io.github.xiaowuiot.iot.jt808.body.RectangleArea;
import io.github.xiaowuiot.iot.jt808.body.Register;
import io.github.xiaowuiot.iot.jt808.body.RegisterResponse;
import io.github.xiaowuiot.iot.jt808.body.Route;
import io.github.xiaowuiot.iot.jt808.body.SimpleBodies;
import io.github.xiaowuiot.iot.jt808.body.TerminalAttributes;
import io.github.xiaowuiot.iot.jt808.body.TerminalControl;
import io.github.xiaowuiot.iot.jt808.body.TerminalParams;
import io.github.xiaowuiot.iot.jt808.body.TextMessage;
import io.github.xiaowuiot.iot.jt808.body.UpgradePackage;
import io.github.xiaowuiot.iot.jt808.body.UpgradeResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息 ID → 消息体解码器。
 * <p>
 * 新增一种消息只需要在这里登记一行，解码器本身不用改。项目里要支持厂家的自定义消息，
 * 调用 {@link #register} 登记即可。
 * <p>
 * 平台下发的消息（0x8xxx）也登记了，因为模拟器作为终端需要解析它们。
 */
public final class BodyDecoders {

    private static final Map<Integer, BodyDecoder<?>> DECODERS = new ConcurrentHashMap<>();

    /**
     * 没有消息体的消息
     */
    private static final BodyDecoder<Object> EMPTY = (body, header) -> null;

    static {
        register(MessageId.TERMINAL_GENERAL_RESPONSE, GeneralResponse::decode);
        register(MessageId.PLATFORM_GENERAL_RESPONSE, GeneralResponse::decode);
        register(MessageId.HEARTBEAT, EMPTY);
        register(MessageId.LOGOUT, EMPTY);
        register(MessageId.REGISTER, Register::decode);
        register(MessageId.REGISTER_RESPONSE, RegisterResponse::decode);
        register(MessageId.AUTH, Auth::decode);
        register(MessageId.LOCATION, Location::decode);
        register(MessageId.LOCATION_BATCH, LocationBatch::decode);
        register(MessageId.LOCATION_QUERY, EMPTY);
        register(MessageId.LOCATION_QUERY_RESPONSE, LocationQueryResponse::decode);
        register(MessageId.SET_PARAMS, TerminalParams::decodeSet);
        register(MessageId.QUERY_PARAMS, EMPTY);
        register(MessageId.QUERY_PARAMS_RESPONSE, TerminalParams::decodeQueryResponse);
        register(MessageId.TERMINAL_CONTROL, TerminalControl::decode);
        register(MessageId.TEXT_MESSAGE, TextMessage::decode);
        register(MessageId.UPGRADE_PACKAGE, UpgradePackage::decode);
        register(MessageId.UPGRADE_RESULT, UpgradeResult::decode);
        register(MessageId.QUERY_PARAMS_BY_ID, SimpleBodies.QueryParamsById::decode);
        register(MessageId.QUERY_ATTRIBUTES, EMPTY);
        register(MessageId.ATTRIBUTES_RESPONSE, TerminalAttributes::decode);
        register(MessageId.TRACKING_CONTROL, SimpleBodies.TrackingControl::decode);
        register(MessageId.ALARM_CONFIRM, SimpleBodies.AlarmConfirm::decode);
        register(MessageId.MULTIMEDIA_EVENT, Multimedia.Event::decode);
        register(MessageId.MULTIMEDIA_DATA, Multimedia.Data::decode);
        register(MessageId.CAMERA_RESPONSE, Multimedia.CameraResponse::decode);
        register(MessageId.MULTIMEDIA_RESPONSE, Multimedia.DataResponse::decode);
        register(MessageId.CAMERA_COMMAND, Multimedia.CameraCommand::decode);
        register(MessageId.SET_CIRCLE_AREA, CircleArea::decode);
        register(MessageId.DELETE_CIRCLE_AREA, CircleArea.Delete::decode);
        register(MessageId.PASS_THROUGH_UP, PassThrough::decodeUplink);
        register(MessageId.PASS_THROUGH_DOWN, PassThrough::decodeDownlink);
        register(MessageId.SET_RECTANGLE_AREA, RectangleArea::decode);
        register(MessageId.DELETE_RECTANGLE_AREA, CircleArea.Delete::decode);
        register(MessageId.SET_POLYGON_AREA, PolygonArea::decode);
        register(MessageId.DELETE_POLYGON_AREA, CircleArea.Delete::decode);
        register(MessageId.SET_ROUTE, Route::decode);
        register(MessageId.DELETE_ROUTE, CircleArea.Delete::decode);
        register(MessageId.RECORDER_COMMAND, Recorder.Command::decode);
        register(MessageId.RECORDER_UPLOAD, Recorder.Upload::decode);
        register(MessageId.DRIVER_INFO_REQUEST, EMPTY);
        register(MessageId.DRIVER_INFO, DriverInfo::decode);
        register(MessageId.EVENT_SETTING, InfoBodies.EventSetting::decode);
        register(MessageId.EVENT_REPORT, InfoBodies.EventReport::decode);
        register(MessageId.QUESTION, InfoBodies.Question::decode);
        register(MessageId.QUESTION_ANSWER, InfoBodies.QuestionAnswer::decode);
        register(MessageId.VEHICLE_CONTROL, InfoBodies.VehicleControl::decode);
        register(MessageId.VEHICLE_CONTROL_RESPONSE, InfoBodies.VehicleControlResponse::decode);
    }

    private BodyDecoders() {
    }

    public static void register(int msgId, BodyDecoder<?> decoder) {
        DECODERS.put(msgId, decoder);
    }

    public static void unregister(int msgId) {
        DECODERS.remove(msgId);
    }

    /**
     * @return null 表示不认识这条消息
     */
    public static BodyDecoder<?> get(int msgId) {
        return DECODERS.get(msgId);
    }
}
