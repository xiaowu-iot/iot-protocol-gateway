package io.github.xiaowuiot.iot.hj212;

/**
 * HJ212-2017 的命令编码（CN）和交互结果。
 * <pre>
 * 类别       CN            含义
 * 初始化     1000          设置超时时间及重发次数
 * 参数       1011 / 1012   提取 / 设置现场机时间
 *           1013          现场机时间校准请求（现场机 → 上位机）
 *           1061 / 1062   提取 / 设置实时数据间隔
 *           1063 / 1064   提取 / 设置分钟数据间隔
 *           1072          设置现场机访问密码
 * 数据       2011 / 2012   实时数据 / 停止察看实时数据
 *           2021 / 2022   设备运行状态 / 停止察看
 *           2031          日历史数据
 *           2041          设备运行时间日历史数据
 *           2051 / 2061   分钟数据 / 小时数据
 *           2081          数采仪开机时间
 * 控制       3011          零点校准、量程校准
 *           3012          即时采样
 *           3013          启动清洗 / 反吹
 *           3014          比对采样
 *           3015          超标留样
 *           3016 / 3017   设置 / 提取采样时间周期
 *           3018          提取出样时间
 *           3019          提取设备唯一标识
 *           3020 / 3021   提取 / 设置现场机信息
 * 交互       9011          请求应答
 *           9012          执行结果
 *           9013          通知应答
 *           9014          数据应答
 * </pre>
 * 具体每条命令 CP 里带哪些字段，以标准原文为准。
 * <p>
 * <b>HJ212-2025</b>（2026-01-01 实施）新增了这些命令：
 * <pre>
 *           1001          上传数采仪硬件序号（现场机 → 上位机，没激活时 MN 为空，上位机回 9013 后下发 1002）
 *           1002          设置数采仪 MN 编码（上位机按硬件指纹给数采仪赋 MN）
 *           1014          现场机获取新密钥 / 上位机下发新密钥，都要加密
 *           2013          原始监测数据
 *           2052          噪声单次测量数据
 *           2062          自动标样核查（校准）数据
 *           2111          上传炉膛温度 5 分钟均值（垃圾焚烧）
 *           3015          改成"上传留样信息"（现场机 → 上位机），2017 版是上位机下发"超标留样"
 *           3022          下发即时留样任务
 *           3024          启动标样核查
 *           9015          心跳包（现场机发，上位机回 9013）
 * </pre>
 */
public final class Hj212Commands {

    public static final String SET_TIMEOUT = "1000";
    public static final String HARDWARE_SN = "1001";
    public static final String SET_MN = "1002";
    public static final String GET_TIME = "1011";
    public static final String SET_TIME = "1012";
    public static final String TIME_CALIBRATION_REQ = "1013";
    public static final String SECRET_KEY = "1014";
    public static final String GET_RTD_INTERVAL = "1061";
    public static final String SET_RTD_INTERVAL = "1062";
    public static final String GET_MIN_INTERVAL = "1063";
    public static final String SET_MIN_INTERVAL = "1064";
    public static final String SET_PASSWORD = "1072";

    public static final String REALTIME = "2011";
    public static final String STOP_REALTIME = "2012";
    public static final String RAW_DATA = "2013";
    public static final String RUN_STATUS = "2021";
    public static final String STOP_RUN_STATUS = "2022";
    public static final String DAY = "2031";
    public static final String RUN_TIME_DAY = "2041";
    public static final String MINUTE = "2051";
    public static final String NOISE_ONCE = "2052";
    public static final String HOUR = "2061";
    public static final String STANDARD_CHECK_DATA = "2062";
    public static final String RESTART_TIME = "2081";
    public static final String FURNACE_TEMP = "2111";

    public static final String CALIBRATE = "3011";
    public static final String SAMPLE_NOW = "3012";
    public static final String CLEAN = "3013";
    public static final String COMPARE_SAMPLE = "3014";
    /**
     * 2017 版：上位机下发"超标留样"；2025 版：现场机上传留样信息
     */
    public static final String OVER_LIMIT_SAMPLE = "3015";
    public static final String SET_SAMPLE_PERIOD = "3016";
    public static final String GET_SAMPLE_PERIOD = "3017";
    public static final String GET_SAMPLE_OUT_TIME = "3018";
    public static final String GET_SN = "3019";
    public static final String GET_INFO = "3020";
    public static final String SET_INFO = "3021";
    public static final String SAMPLE_TASK = "3022";
    public static final String START_STANDARD_CHECK = "3024";

    public static final String REQUEST_ACK = "9011";
    public static final String EXEC_RESULT = "9012";
    public static final String NOTIFY_ACK = "9013";
    public static final String DATA_ACK = "9014";
    public static final String HEARTBEAT = "9015";

    // ===== 请求应答 QnRtn =====
    public static final int QN_READY = 1;
    public static final int QN_REJECTED = 2;
    public static final int QN_BAD_PW = 3;
    public static final int QN_BAD_MN = 4;
    public static final int QN_BAD_ST = 5;
    public static final int QN_BAD_FLAG = 6;
    public static final int QN_BAD_QN = 7;
    public static final int QN_BAD_CN = 8;
    public static final int QN_BAD_CRC = 9;
    public static final int QN_UNKNOWN = 100;

    // ===== 执行结果 ExeRtn =====
    public static final int EXE_SUCCESS = 1;
    public static final int EXE_FAILED = 2;
    public static final int EXE_BAD_CONDITION = 3;
    public static final int EXE_TIMEOUT = 4;
    public static final int EXE_BUSY = 5;
    public static final int EXE_FAULT = 6;
    public static final int EXE_NO_DATA = 100;

    private Hj212Commands() {
    }

    public static String qnRtnName(int v) {
        switch (v) {
            case QN_READY:
                return "准备执行请求";
            case QN_REJECTED:
                return "请求被拒绝";
            case QN_BAD_PW:
                return "PW 错误";
            case QN_BAD_MN:
                return "MN 错误";
            case QN_BAD_ST:
                return "ST 错误";
            case QN_BAD_FLAG:
                return "Flag 错误";
            case QN_BAD_QN:
                return "QN 错误";
            case QN_BAD_CN:
                return "CN 错误";
            case QN_BAD_CRC:
                return "CRC 校验错误";
            default:
                return "未知错误";
        }
    }

    public static String exeRtnName(int v) {
        switch (v) {
            case EXE_SUCCESS:
                return "执行成功";
            case EXE_FAILED:
                return "执行失败，但不知道原因";
            case EXE_BAD_CONDITION:
                return "命令请求条件错误";
            case EXE_TIMEOUT:
                return "通讯超时";
            case EXE_BUSY:
                return "系统繁忙不能执行";
            case EXE_FAULT:
                return "系统故障";
            case EXE_NO_DATA:
                return "没有数据";
            default:
                return "未知结果 " + v;
        }
    }

    // ===== 数据标记 xxx-Flag =====

    /**
     * 2017 版数据标记：N 正常、F 停运、M 维护、S 手工输入、D 故障、C 校准、T 超限、B 通讯异常。
     * <p>
     * 2025 版把标记改成最长 3 个字符、区分大小写，分两类：
     * <ul>
     *     <li>自动监测设备标记：N 数据有效、D 故障、C 校准、M 日常维护、T 超量程、Td 关键工况参数故障</li>
     *     <li>生产工况标记：N 正常运行、St 启动（Stc 冷启动、Sth 热启动）、Sd 停炉（机）、B 闷炉、
     *     F 停运 / 停排、Sr 故障 / 事故</li>
     * </ul>
     * 注意 B 在两版里含义不同：2017 版是"通讯异常"，2025 版是"闷炉"，按版本取名称用 {@link #flagName(String, int)}。
     * <p>
     * 停运、维护、故障、校准、通讯异常、启停炉时的数据不是正常工况下的排放，<b>不能参与均值计算和超标判断</b>；
     * 超限（T）是仪器正常、但数值超出了量程或限值，数据本身有效；故障 / 事故（Sr）时仍在排放，照常判断。
     */
    public static boolean isValidFlag(String flag) {
        return flag == null || "N".equals(flag) || "T".equals(flag) || "S".equals(flag) || "Sr".equals(flag);
    }

    /**
     * 按协议版本取标记的中文名：B 在 2025 版是闷炉
     */
    public static String flagName(String flag, int version) {
        if (version >= Hj212Packet.VERSION_2025 && "B".equals(flag)) {
            return "闷炉";
        }
        return flagName(flag);
    }

    public static String flagName(String flag) {
        if (flag == null) {
            return "未标记";
        }
        switch (flag) {
            case "Td":
                return "关键工况参数故障";
            case "St":
                return "启动";
            case "Stc":
                return "冷启动";
            case "Sth":
                return "热启动";
            case "Sd":
                return "停炉（机）";
            case "Sr":
                return "故障 / 事故";
            case "N":
                return "正常";
            case "F":
                return "停运";
            case "M":
                return "维护";
            case "S":
                return "手工输入";
            case "D":
                return "故障";
            case "C":
                return "校准";
            case "T":
                return "超限";
            case "B":
                return "通讯异常";
            default:
                return "未知标记 " + flag;
        }
    }

    /**
     * 是不是数据上传类命令（2xxx）
     */
    public static boolean isData(String cn) {
        return cn != null && cn.startsWith("2");
    }
}
