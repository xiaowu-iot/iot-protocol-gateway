package io.github.xiaowuiot.iot.simulator.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令编码、系统编码的中文名，日志和页面上显示用。
 * 系统编码按 HJ212-2025 表 7，命令编码合并了 2017 版表 9 和 2025 版表 12。
 */
final class Hj212Names {

    static final Map<String, String> ST = new LinkedHashMap<>();
    static final Map<String, String> CN = new LinkedHashMap<>();

    static {
        ST.put("21", "地表水环境质量监测");
        ST.put("22", "环境空气质量监测");
        ST.put("23", "声环境质量监测");
        ST.put("24", "地下水环境质量监测");
        ST.put("25", "土壤环境质量监测");
        ST.put("26", "海水质量监测");
        ST.put("27", "挥发性有机物监测");
        ST.put("28", "自动化实验室监测");
        ST.put("31", "大气环境污染源");
        ST.put("32", "地表水体环境污染源");
        ST.put("33", "地下水体环境污染源");
        ST.put("34", "海洋环境污染源");
        ST.put("35", "土壤环境污染源");
        ST.put("36", "声环境污染源");
        ST.put("37", "振动环境污染源");
        ST.put("38", "放射性环境污染源");
        ST.put("39", "工地扬尘污染源");
        ST.put("41", "电磁环境污染源");
        ST.put("42", "废气污染物排放过程（工况）监控");
        ST.put("43", "废水污染物排放过程（工况）监控");
        ST.put("44", "设施用电监控");
        ST.put("45", "关键生产工况监控");
        ST.put("51", "温室气体排放监测系统");
        ST.put("52", "碳足迹跟踪系统");
        ST.put("71", "河流生态系统");
        ST.put("72", "湖泊和水库生态系统");
        ST.put("73", "森林生态系统");
        ST.put("74", "灌丛生态系统");
        ST.put("75", "草原生态系统");
        ST.put("76", "湿地生态系统");
        ST.put("77", "农田生态系统");
        ST.put("78", "城镇生态系统");
        ST.put("79", "荒漠生态系统");
        ST.put("91", "系统交互");

        CN.put("1000", "设置超时时间及重发次数");
        CN.put("1001", "上传数采仪硬件序号");
        CN.put("1002", "设置数采仪 MN 编码");
        CN.put("1011", "提取现场机时间");
        CN.put("1012", "设置现场机时间");
        CN.put("1013", "现场机时间校准请求");
        CN.put("1014", "密钥更新");
        CN.put("1061", "提取实时数据间隔");
        CN.put("1062", "设置实时数据间隔");
        CN.put("1063", "提取分钟数据间隔");
        CN.put("1064", "设置分钟数据间隔");
        CN.put("1072", "设置现场机访问密码");
        CN.put("2011", "实时数据");
        CN.put("2012", "停止察看实时数据");
        CN.put("2013", "原始监测数据");
        CN.put("2021", "设备运行状态");
        CN.put("2022", "停止察看设备运行状态");
        CN.put("2031", "日数据");
        CN.put("2041", "设备运行时间日数据");
        CN.put("2051", "分钟数据");
        CN.put("2052", "噪声单次测量数据");
        CN.put("2061", "小时数据");
        CN.put("2062", "自动标样核查（校准）数据");
        CN.put("2081", "数采仪开机时间");
        CN.put("2111", "炉膛温度 5 分钟均值");
        CN.put("3011", "零点 / 量程校准");
        CN.put("3012", "即时采样");
        CN.put("3013", "启动清洗 / 反吹");
        CN.put("3014", "比对采样");
        CN.put("3015", "超标留样 / 上传留样信息");
        CN.put("3016", "设置采样时间周期");
        CN.put("3017", "提取采样时间周期");
        CN.put("3018", "提取出样时长");
        CN.put("3019", "设备唯一标识");
        CN.put("3020", "现场机信息");
        CN.put("3021", "设置现场机参数");
        CN.put("3022", "下发即时留样任务");
        CN.put("3024", "启动自动标样核查");
        CN.put("9011", "请求应答");
        CN.put("9012", "执行结果");
        CN.put("9013", "通知应答");
        CN.put("9014", "数据应答");
        CN.put("9015", "心跳包");
    }

    private Hj212Names() {
    }

    static String cn(String cn) {
        return cn == null ? "" : CN.getOrDefault(cn, "命令 " + cn);
    }

    static String st(String st) {
        return st == null ? "" : ST.getOrDefault(st, "");
    }

    static String version(int v) {
        switch (v) {
            case 0:
                return "2005";
            case 1:
                return "2017";
            case 2:
                return "2025";
            default:
                return "版本 " + v;
        }
    }
}
