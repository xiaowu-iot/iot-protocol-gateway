package io.github.xiaowuiot.iot.hj212;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HJ212 数据段。
 * <pre>
 * QN=20160801085857223;ST=32;CN=2011;PW=123456;MN=010000A8900016F000169DC0;Flag=5;CP=&amp;&amp;DataTime=20160801085857;w01018-Rtd=101.5,w01018-Flag=N&amp;&amp;
 * </pre>
 * <ul>
 *     <li>QN 请求编码，yyyyMMddHHmmssSSS，应答时原样带回，用它匹配请求和应答</li>
 *     <li>ST 系统编码：21 地表水、22 空气、31 大气环境污染源、32 地表水体环境污染源、91 系统交互</li>
 *     <li>CN 命令编码：2011 实时数据、2051 分钟数据、2061 小时数据、2031 日数据、9014 数据应答……</li>
 *     <li>MN 设备唯一标识</li>
 *     <li>Flag：bit0 是否需要应答，bit1 是否分包，bit2~7 协议版本（2017 版为 1）</li>
 *     <li>CP 指令参数，{@code &&} 包起来，组之间用分号、组内用逗号分隔</li>
 * </ul>
 */
public class Hj212Packet {

    public static final String CN_REALTIME = "2011";
    public static final String CN_MINUTE = "2051";
    public static final String CN_HOUR = "2061";
    public static final String CN_DAY = "2031";
    public static final String CN_REQUEST_ACK = "9011";
    public static final String CN_EXEC_RESULT = "9012";
    public static final String CN_NOTIFY_ACK = "9013";
    public static final String CN_DATA_ACK = "9014";

    public static final String ST_SYSTEM = "91";

    /**
     * 2017 版的版本号是 1，写在 Flag 的 bit2~7
     */
    public static final int VERSION_2017 = 1;
    public static final int FLAG_NEED_ACK = 1;
    public static final int FLAG_SPLIT = 1 << 1;

    private String qn;
    private String st;
    private String cn;
    private String pw;
    private String mn;
    private int flag;
    private Integer pnum;
    private Integer pno;
    /**
     * 是不是对平台某个请求的回复数据（比如平台提取的历史数据）。不在报文里，接收时由服务端标记
     */
    private boolean requested;

    /**
     * CP 里不属于某个监测因子的字段，比如 DataTime、RtdInterval
     */
    private final Map<String, String> params = new LinkedHashMap<>();

    /**
     * 监测因子：因子编码 → 字段（Rtd、Avg、Min、Max、Cou、Flag……）→ 值。
     * 比如 {@code w01018-Rtd=101.5} 存成 {@code w01018 → {Rtd → 101.5}}
     */
    private final Map<String, Map<String, String>> factors = new LinkedHashMap<>();

    public boolean isRequested() {
        return requested;
    }

    public Hj212Packet markRequested() {
        this.requested = true;
        return this;
    }

    public boolean needAck() {
        return (flag & FLAG_NEED_ACK) != 0;
    }

    public boolean isSplit() {
        return (flag & FLAG_SPLIT) != 0;
    }

    public int version() {
        return flag >> 2;
    }

    /**
     * 生成对这条数据的应答 9014：QN 原样带回
     */
    public Hj212Packet dataAck() {
        Hj212Packet ack = new Hj212Packet();
        ack.qn = qn;
        ack.st = ST_SYSTEM;
        ack.cn = CN_DATA_ACK;
        ack.pw = pw;
        ack.mn = mn;
        // 应答用和对方一样的版本：2005 版的数采仪不认识 2017 版的版本号
        ack.flag = version() << 2;
        return ack;
    }

    /**
     * 生成一条和本包"同一次交互"的回复：QN、MN、PW 原样带回，ST 用系统交互 91。
     * 9011 请求应答、9012 执行结果、9013 通知应答都这样组包；命令返回的数据（比如 1011 的现场机时间）
     * 用原来的 CN 和 ST，见 {@link #dataReply()}
     */
    public Hj212Packet reply(String replyCn) {
        Hj212Packet r = new Hj212Packet();
        r.qn = qn;
        r.st = ST_SYSTEM;
        r.cn = replyCn;
        r.pw = pw;
        r.mn = mn;
        r.flag = version() << 2;
        return r;
    }

    /**
     * 命令返回的数据包：QN、ST、CN 都和请求一样
     */
    public Hj212Packet dataReply() {
        Hj212Packet r = reply(cn);
        r.st = st;
        return r;
    }

    public Hj212Packet removeParam(String key) {
        params.remove(key);
        return this;
    }

    public String getParam(String key) {
        return params.get(key);
    }

    public Hj212Packet putParam(String key, String value) {
        params.put(key, value);
        return this;
    }

    public Hj212Packet putFactor(String code, String field, String value) {
        factors.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(field, value);
        return this;
    }

    public Map<String, String> getParams() {
        return Collections.unmodifiableMap(params);
    }

    public Map<String, Map<String, String>> getFactors() {
        return Collections.unmodifiableMap(factors);
    }

    public String getQn() {
        return qn;
    }

    public Hj212Packet setQn(String qn) {
        this.qn = qn;
        return this;
    }

    public String getSt() {
        return st;
    }

    public Hj212Packet setSt(String st) {
        this.st = st;
        return this;
    }

    public String getCn() {
        return cn;
    }

    public Hj212Packet setCn(String cn) {
        this.cn = cn;
        return this;
    }

    public String getPw() {
        return pw;
    }

    public Hj212Packet setPw(String pw) {
        this.pw = pw;
        return this;
    }

    public String getMn() {
        return mn;
    }

    public Hj212Packet setMn(String mn) {
        this.mn = mn;
        return this;
    }

    public int getFlag() {
        return flag;
    }

    public Hj212Packet setFlag(int flag) {
        this.flag = flag;
        return this;
    }

    public Integer getPnum() {
        return pnum;
    }

    public Hj212Packet setPnum(Integer pnum) {
        this.pnum = pnum;
        return this;
    }

    public Integer getPno() {
        return pno;
    }

    public Hj212Packet setPno(Integer pno) {
        this.pno = pno;
        return this;
    }

    @Override
    public String toString() {
        return "Hj212Packet{CN=" + cn + ", MN=" + mn + ", QN=" + qn + ", Flag=" + flag + ", params=" + params
                + ", factors=" + factors + '}';
    }
}
