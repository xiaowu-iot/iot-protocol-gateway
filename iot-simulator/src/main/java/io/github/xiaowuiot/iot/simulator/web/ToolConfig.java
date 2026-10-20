package io.github.xiaowuiot.iot.simulator.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟设备的配置。页面上改的、平台用命令改的（密码、MN、密钥、数据间隔、时钟……）都记在这里，页面随时能看到当前值
 */
public class ToolConfig {

    public static class Factor {
        public String code;
        /**
         * 生成数据时围绕这个值上下波动
         */
        public double base;
        public int decimals = 2;

        public Factor() {
        }

        public Factor(String code, double base, int decimals) {
            this.code = code;
            this.base = base;
            this.decimals = decimals;
        }
    }

    // ===== 连接 =====
    public String host = "127.0.0.1";
    public int port = 7613;
    public boolean reconnect = true;

    // ===== 身份 =====
    /**
     * 协议版本：2005 / 2017 / 2025，决定 Flag 里的版本位（0 / 1 / 2）
     */
    public int version = 2017;
    public String mn = "010000A8900016F000169DC0";
    public String pw = "123456";
    public String st = "32";
    /**
     * 2025 版数采仪唯一标识（i23005），没激活时上传 1001、平台用 1002 按它分配 MN
     */
    public String hardwareSn = "6970000000001ABCDEFG1234";

    // ===== 2025 版加密 =====
    /**
     * 通信密钥，16 个字符。空表示不加密
     */
    public String key = "";
    /**
     * 有密钥时，按标准要求加密数据命令（2xxx）、1014、3020
     */
    public boolean encrypt = true;

    // ===== 行为 =====
    public boolean autoReply = true;
    /**
     * 上传数据要求平台应答（Flag 的 A 位）
     */
    public boolean dataNeedAck = true;
    /**
     * 平台提取的历史数据，回包是否要求应答（2025 附录 C.55 不要求、C.56 要求）
     */
    public boolean historyNeedAck = false;
    /**
     * 接下来的几条平台命令假装没收到，看平台会不会重发
     */
    public int dropCommands = 0;
    /**
     * 等平台应答的超时时间（秒）和重发次数，平台可以用 1000 命令修改
     */
    public int overTime = 5;
    public int reCount = 3;
    /**
     * 现场机时钟偏差（秒），平台用 1012 校时会改它
     */
    public long clockOffset = 0;

    // ===== 自动任务 =====
    public boolean realtimeAuto = false;
    public int rtdInterval = 30;
    public boolean minuteAuto = false;
    public int minInterval = 10;
    /**
     * 整点上传小时数据，零点上传日数据
     */
    public boolean hourAuto = false;
    /**
     * 2025 版心跳包间隔（秒），0 不发
     */
    public int heartbeatSeconds = 0;
    /**
     * 连上后发 1013 请求校时
     */
    public boolean calibrateOnConnect = false;

    public List<Factor> factors = new ArrayList<>();

    public ToolConfig() {
        factors.add(new Factor("w00000", 17.5, 1));
        factors.add(new Factor("w01018", 45.0, 1));
        factors.add(new Factor("w21003", 1.5, 2));
        factors.add(new Factor("w21011", 0.3, 3));
        factors.add(new Factor("w01001", 7.2, 2));
    }

    int versionBits() {
        return version == 2025 ? 2 : version == 2005 ? 0 : 1;
    }
}
