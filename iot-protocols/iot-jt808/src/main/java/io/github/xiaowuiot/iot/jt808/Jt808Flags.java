package io.github.xiaowuiot.iot.jt808;

import java.util.ArrayList;
import java.util.List;

/**
 * 0x0200 的报警标志位和状态位，按 JT/T 808-2013 的定义翻译成文字。
 * <p>
 * 报警位有两种清除方式：大部分"维持到报警条件解除"，终端自己清；
 * 少数几位"收到应答后清零"，需要平台用 0x8203 人工确认，见 {@link #CONFIRM_REQUIRED}。
 */
public final class Jt808Flags {

    private static final String[] ALARMS = {
            "紧急报警", "超速报警", "疲劳驾驶", "危险预警",
            "GNSS 模块故障", "GNSS 天线未接或被剪断", "GNSS 天线短路", "终端主电源欠压",
            "终端主电源掉电", "终端显示器故障", "TTS 模块故障", "摄像头故障",
            "道路运输证 IC 卡模块故障", "超速预警", "疲劳驾驶预警", null,
            null, null, "当天累计驾驶超时", "超时停车",
            "进出区域", "进出路线", "路段行驶时间不足或过长", "路线偏离报警",
            "车辆 VSS 故障", "车辆油量异常", "车辆被盗", "车辆非法点火",
            "车辆非法位移", "碰撞预警", "侧翻预警", "非法开门报警"
    };

    /**
     * 需要平台确认后才清零的报警位：紧急报警、危险预警、进出区域、进出路线、路段行驶时间、非法点火、非法位移
     */
    public static final long CONFIRM_REQUIRED = 1L | 1L << 3 | 1L << 20 | 1L << 21 | 1L << 22 | 1L << 27 | 1L << 28;

    private Jt808Flags() {
    }

    public static String alarmName(int bit) {
        String s = bit >= 0 && bit < 32 ? ALARMS[bit] : null;
        return s != null ? s : "保留位 " + bit;
    }

    public static List<String> alarmNames(long alarm) {
        List<String> list = new ArrayList<>();
        for (int bit = 0; bit < 32; bit++) {
            if ((alarm >> bit & 1) == 1) {
                list.add(alarmName(bit));
            }
        }
        return list;
    }

    /**
     * 状态位翻译成文字，只列出有意义的状态
     */
    public static List<String> statusNames(long status) {
        List<String> list = new ArrayList<>();
        list.add(bit(status, 0) ? "ACC 开" : "ACC 关");
        list.add(bit(status, 1) ? "已定位" : "未定位");
        if (bit(status, 4)) {
            list.add("停运");
        }
        if (bit(status, 5)) {
            list.add("经纬度已加密");
        }
        int load = (int) (status >> 8 & 0b11);
        if (load == 0b01) {
            list.add("半载");
        } else if (load == 0b11) {
            list.add("满载");
        }
        if (bit(status, 10)) {
            list.add("油路断开");
        }
        if (bit(status, 11)) {
            list.add("电路断开");
        }
        if (bit(status, 12)) {
            list.add("车门加锁");
        }
        String[] doors = {"前门开", "中门开", "后门开", "驾驶席门开", "自定义门开"};
        for (int i = 0; i < doors.length; i++) {
            if (bit(status, 13 + i)) {
                list.add(doors[i]);
            }
        }
        StringBuilder gnss = new StringBuilder();
        String[] systems = {"GPS", "北斗", "GLONASS", "Galileo"};
        for (int i = 0; i < systems.length; i++) {
            if (bit(status, 18 + i)) {
                gnss.append(gnss.length() == 0 ? "" : "+").append(systems[i]);
            }
        }
        if (gnss.length() > 0) {
            list.add("定位系统 " + gnss);
        }
        return list;
    }

    private static boolean bit(long v, int bit) {
        return (v >> bit & 1) == 1;
    }
}
