# iot-protocol-gateway

基于 Netty 的国标 IoT 协议接入网关。支持 **JT/T 808（2013 / 2019）、HJ212-2017** 和一个示例私有协议 **SLP**，自带**设备模拟器**：没有真机也能跑起来。

> 🚧 项目处于早期阶段，接口可能还会调整。欢迎 Star 关注进度。
>
> 仓库地址：[GitHub](https://github.com/xiaowu-iot/iot-protocol-gateway) · [Gitee（国内镜像）](https://gitee.com/xiaowu-iot/iot-protocol-gateway)

## 为什么做这个项目

做过车联网、环保监测或智能硬件对接的同学大概都有体会：

- 协议文档几十页，真正对接时才发现转义、分包、版本差异这些细节处处是坑；
- 网上的资料东一篇西一篇，很少有从 **报文结构 → Netty 实现 → 跑起来看效果** 讲完整的；
- 手上没有终端设备，想调试只能等现场联调。

这个项目想把这些问题一次解决：**协议库可以直接拿去用，模拟器可以直接拿来调，文档把原理讲透。**

## 特性

- **协议库零依赖 Spring**：纯 Java + Netty，兼容 JDK 8，可以直接嵌入现有系统
- **JT/T 808**：注册、鉴权、心跳、位置上报、批量补传；参数查询设置、文本下发、终端控制、拍照、透传等常用指令；分包合包；2013 / 2019 双版本
- **HJ212-2017**：实时数据，分钟、小时、日数据，常用反控指令，CRC 校验
- **SLP 示例协议**：一个完整设计的私有二进制协议（[协议文档](iot-protocols/iot-slp/PROTOCOL.md)），演示怎样从零设计、实现私有协议
- **编解码基础设施**：转义帧、长度帧、ASCII 帧解码器，半包、粘包、垃圾数据处理，指令应答匹配，会话管理
- **网关应用**：Spring Boot 3，统一消息模型，输出到日志 / Kafka / Webhook，HTTP 管理接口下发指令
- **连接记录和指令记录**：每台设备的连接、注册鉴权（成功失败和原因）、上下线、断开（心跳超时、连接异常），通过接口下发的每条指令和设备应答，都能按设备查
- **设备模拟器**：一条命令模拟成百上千台 808 终端、212 数采仪、储物柜，带随机轨迹、断线重连、盲区补传、紧急报警等场景

## 快速开始

环境：JDK 17+、Maven 3.6+（只构建协议库的话 JDK 8 也可以）。

```bash
git clone https://github.com/xiaowu-iot/iot-protocol-gateway.git
# 国内访问慢的话用 Gitee 镜像：
# git clone https://gitee.com/xiaowu-iot/iot-protocol-gateway.git
cd iot-protocol-gateway
mvn clean package -DskipTests
```

启动网关：

```bash
java -jar iot-gateway/target/iot-gateway-0.1.0-SNAPSHOT.jar
```

另开一个终端，启动 10 台模拟 808 终端，每 10 秒上报一次位置：

```bash
java -jar iot-simulator/target/iot-simulator-0.1.0-SNAPSHOT.jar jt808 --count 10 --interval 10
```

网关日志里就能看到终端注册、鉴权、上线和位置数据。查询在线设备、查询某台终端的位置：

```bash
curl http://localhost:8080/api/devices
curl http://localhost:8080/api/jt808/13800138000/location
```

查某台终端的连接记录（连接、鉴权、上下线、断开）和指令记录：

```bash
curl "http://localhost:8080/api/audit/connections?deviceId=13800138000"
curl "http://localhost:8080/api/audit/commands?deviceId=13800138000"
# 只看鉴权失败的、只看没成功的指令
curl "http://localhost:8080/api/audit/connections?event=AUTH&success=false"
curl "http://localhost:8080/api/audit/commands?success=false"
```

还可以加 `protocol`（jt808 / hj212 / slp）、`since`（如 `2026-10-20T08:00:00Z`）、`limit`（默认 100）。内存里只留最近 5000 条（`iot.audit.max-records`），每条记录同时写到 `logs/iot-audit.log`（一行一条 JSON，保留 90 天），更早的到文件里查。

## 项目结构

```
iot-protocol-gateway
├── iot-protocols          协议库（JDK 8+）
│   ├── iot-codec-core     编解码基础：帧解码、校验、会话、指令应答匹配
│   ├── iot-jt808          JT/T 808-2013 / 2019
│   ├── iot-jt1078         JT/T 1078 音视频（规划中）
│   ├── iot-hj212          HJ212-2017
│   └── iot-slp            SLP 智能储物柜协议（示例私有协议）
├── iot-gateway            接入网关应用（Spring Boot 3，JDK 17+）
├── iot-simulator          设备模拟器（JDK 17+）
└── iot-docs               文档和入门文章
```

## 默认端口

| 服务 | 端口 |
|---|---|
| JT/T 808 | 7611 |
| HJ212 | 7613 |
| SLP | 7614 |
| HTTP 管理接口 | 8080 |

## 入门文章

《Java 程序员的 IoT 协议入门》系列，写给想转物联网的 Java 程序员：

1. [物联网平台到底在做什么](iot-docs/docs/articles/01-what-is-iot-platform.md)
2. [设备是怎么连上平台的：TCP、MQTT、HTTP、DTU](iot-docs/docs/articles/02-how-devices-connect.md)
3. [国内的 IoT 为什么绕不开国标协议](iot-docs/docs/articles/03-why-national-standards.md)
4. [二进制基础：Java 程序员读协议的第一关](iot-docs/docs/articles/04-binary-basics.md)
5. [手把手解析第一条 JT/T 808 报文](iot-docs/docs/articles/05-parse-first-jt808-message.md)
6. [TCP 拆包粘包：设备接入绕不开的第一个坑](iot-docs/docs/articles/06-tcp-sticky-packets.md)

## 路线图

✅ 已完成 · 🚧 进行中 · 📋 计划中

### 协议详解

每个协议一篇详解，从报文结构讲到 Java 实现，再到现场对接时踩过的坑：

| 协议 | 场景 | 状态 |
|---|---|:---:|
| JT/T 808（2013 / 2019） | 车载终端：定位、报警、指令、多媒体 | 🚧 |
| HJ212-2017 | 环保在线监测：数采仪上报、反控 | 🚧 |
| SLP 示例协议 | 从零设计一个私有二进制协议 | 🚧 |
| JT/T 808 行业扩展 | 主动安全（ADAS / DSM）报警附件、油箱油量、加油偷油识别 | 📋 |
| JT/T 809 | 平台与平台之间的数据转发 | 📋 |
| JT/T 1078 | 车载音视频 | 📋 |
| Modbus RTU / TCP | 工业现场：点表、字序、轮询 | 📋 |
| DL/T 645、CJ/T 188 | 电表、水表、热量表抄表 | 📋 |
| GB 26875 | 城市消防远程监控 | 📋 |
| MQTT | 新设备的主流接入方式，和 TCP 私有协议的取舍 | 📋 |
| DTU 透传 | 串口设备通过 4G 联网：注册包、心跳包、多设备排队 | 📋 |

### 平台能力

- 📋 OTA 远程升级：断点续传、限流、失败止损
- 📋 轨迹存储与回放、电子围栏
- 📋 监控运维：连接数、吞吐量、报文日志、告警
- 📋 网关集群与单机 10 万长连接压测

### AI 视觉识别（智慧环卫）

摄像头拍到的画面，用训练好的模型自动识别问题，识别结果通过网关上报平台、生成告警和工单：

| 识别场景 | 说明 | 状态 |
|---|---|:---:|
| 垃圾桶满溢 | 桶内垃圾超过桶口，提醒及时清运 | 📋 |
| 垃圾暴露 | 垃圾散落在桶外、未按要求收集 | 📋 |
| 地面垃圾 | 道路、广场上的纸屑、塑料袋等散落垃圾 | 📋 |
| 占道经营 | 流动摊贩占用道路、人行道经营 | 📋 |

每个场景都会讲完整流程：采集图片 → 数据标注 → 模型训练 → 评估调优 → 部署到边缘盒子或服务端 → 识别结果接入物联网平台告警。

## 想系统学、做完整的平台？

这个仓库是**基础版**：协议库、网关骨架和模拟器，够你把设备接进来、跑通整个流程。

真实项目里还有很多仓库里没有的东西。我在知识星球 **「小物学长的物联网实战营」** 里，带大家 20 周从零做出一个完整的物联网接入平台，代码比这里全得多：

| 内容 | 仓库（基础版） | 实战营 |
|---|:---:|:---:|
| 808 注册 / 鉴权 / 心跳 / 位置 / 常用指令 | ✅ | ✅ 逐步讲解 |
| HJ212、SLP 示例协议 | ✅ | ✅ 逐步讲解 |
| 设备模拟器 | ✅ | ✅ |
| 808 行业扩展：主动安全（ADAS / DSM）报警附件、油箱油量、加油偷油识别 | | ✅ |
| OTA 远程升级：断点续传、限流、失败止损 | | ✅ |
| JT/T 809 上级平台转发 | | ✅ |
| DTU 透传与轮询：Modbus、DL/T 645 电表、CJ/T 188 水表 | | ✅ |
| GB 26875 消防、轨迹存储 | | ✅ |
| 监控运维、网关集群、单机 10 万连接压测 | | ✅ |
| 学习路线、答疑 | | ✅ |

👉 加入方式：[知识星球「小物学长的物联网实战营」](https://t.zsxq.com/hldoY)，或者关注公众号「**小物学长**」，回复【星球】。

公众号回复【速查】可以领取《IoT 协议速查手册》PDF。

<p align="center">
  <img src=".github/assets/wechat-qrcode.jpg" width="215" alt="公众号「小物学长」二维码"><br>
  微信扫码关注公众号「小物学长」
</p>

## 参与贡献

欢迎提交 Issue 和 PR。如果你手上有真实设备的报文样例（请脱敏后再提交），对完善协议兼容性帮助很大。

## License

[Apache License 2.0](LICENSE)
