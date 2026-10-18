package io.github.xiaowuiot.iot.jt808;

/**
 * 常用消息 ID。终端发给平台的以 0 开头，平台发给终端的以 8 开头。
 */
public final class MessageId {

    public static final int TERMINAL_GENERAL_RESPONSE = 0x0001;
    public static final int HEARTBEAT = 0x0002;
    public static final int LOGOUT = 0x0003;
    public static final int REGISTER = 0x0100;
    public static final int AUTH = 0x0102;
    public static final int QUERY_PARAMS_RESPONSE = 0x0104;
    public static final int ATTRIBUTES_RESPONSE = 0x0107;
    public static final int UPGRADE_RESULT = 0x0108;
    public static final int LOCATION = 0x0200;
    public static final int LOCATION_QUERY_RESPONSE = 0x0201;
    public static final int EVENT_REPORT = 0x0301;
    public static final int QUESTION_ANSWER = 0x0302;
    public static final int VEHICLE_CONTROL_RESPONSE = 0x0500;
    public static final int RECORDER_UPLOAD = 0x0700;
    public static final int DRIVER_INFO = 0x0702;
    public static final int LOCATION_BATCH = 0x0704;
    public static final int MULTIMEDIA_EVENT = 0x0800;
    public static final int MULTIMEDIA_DATA = 0x0801;
    public static final int CAMERA_RESPONSE = 0x0805;
    public static final int PASS_THROUGH_UP = 0x0900;

    public static final int PLATFORM_GENERAL_RESPONSE = 0x8001;
    public static final int REGISTER_RESPONSE = 0x8100;
    public static final int SET_PARAMS = 0x8103;
    public static final int QUERY_PARAMS = 0x8104;
    public static final int QUERY_PARAMS_BY_ID = 0x8106;
    public static final int QUERY_ATTRIBUTES = 0x8107;
    public static final int TERMINAL_CONTROL = 0x8105;
    public static final int UPGRADE_PACKAGE = 0x8108;
    public static final int LOCATION_QUERY = 0x8201;
    public static final int TRACKING_CONTROL = 0x8202;
    public static final int ALARM_CONFIRM = 0x8203;
    public static final int TEXT_MESSAGE = 0x8300;
    public static final int EVENT_SETTING = 0x8301;
    public static final int QUESTION = 0x8302;
    public static final int VEHICLE_CONTROL = 0x8500;
    public static final int SET_CIRCLE_AREA = 0x8600;
    public static final int DELETE_CIRCLE_AREA = 0x8601;
    public static final int SET_RECTANGLE_AREA = 0x8602;
    public static final int DELETE_RECTANGLE_AREA = 0x8603;
    public static final int SET_POLYGON_AREA = 0x8604;
    public static final int DELETE_POLYGON_AREA = 0x8605;
    public static final int SET_ROUTE = 0x8606;
    public static final int DELETE_ROUTE = 0x8607;
    public static final int RECORDER_COMMAND = 0x8700;
    public static final int DRIVER_INFO_REQUEST = 0x8702;
    public static final int MULTIMEDIA_RESPONSE = 0x8800;
    public static final int CAMERA_COMMAND = 0x8801;
    public static final int PASS_THROUGH_DOWN = 0x8900;

    private MessageId() {
    }
}
