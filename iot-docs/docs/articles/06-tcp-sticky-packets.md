# Java 程序员的 IoT 协议入门（六）：TCP 拆包粘包，设备接入绕不开的第一个坑

## 上一篇练习的答案

```
7E 00 02 00 00 01 38 00 13 80 00 00 7D 02 D6 7E
```

1. 去掉首尾的 7E，把 `7D 02` 还原成 `7E`，得到原始内容：`00 02 00 00 01 38 00 13 80 00 00 7E D6`
2. 流水号是 `00 7E`，也就是 126。正因为它包含 7E，所以需要转义
3. 对 `00 02 00 00 01 38 00 13 80 00 00 7E` 逐字节异或，结果是 `0xD6`，和校验码一致，校验正确

---

上一篇我们手工解析了一条完整的报文。现在把它搬到代码里，很多人写出来的第一版大概是这样的：

```java
public class Jt808Handler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        // 以为每次收到的就是一条完整的报文
        Jt808Message message = parse(buf);
        handle(message);
    }
}
```

本地用调试工具一条一条地发，测试完全正常。一上线就开始出现各种奇怪的问题：偶尔解析失败、校验码不对、数据错乱，而且没办法稳定复现。

我第一次遇到这个问题是在环卫项目里接收运车辆的车载终端。测试环境里几台终端跑得好好的，车辆一批批装上终端以后，日志里开始零星地出现"校验码错误""消息头解析失败"，轨迹上偶尔丢几个点。出问题的往往是信号不好的路段：终端攒了一段数据，网络一恢复就一口气发上来，几条报文挤在一起；或者一条长报文被拆成两段到达。

本地测不出来，是因为调试工具一次发一条、网络又好，每次收到的正好是完整的一条。

这就是著名的 **TCP 拆包粘包**问题。

## 现象：收到的数据和发出的报文对不上

设备连续发了三条报文 A、B、C，服务端收到的数据可能是下面任何一种情况：

```
设备发送：     [  A  ][  B  ][  C  ]

情况 1：       [  A  ][  B  ][  C  ]          正好一次一条（本地测试时的情况）
情况 2：       [  A  B  ][  C  ]              粘包：两条报文一次到达
情况 3：       [  A  ][ B 的前半 ][ B 的后半 C ]  拆包：一条报文分两次到达
情况 4：       [ A B 的前半 ][ B 的后半 ][ C ]    拆包和粘包同时发生
```

上面那段代码只能正确处理情况 1。

## 原因：TCP 是"流"，不是"包"

很多人会说"TCP 发生了粘包"，好像这是 TCP 的 bug。其实不是。

**TCP 从设计上就是一个字节流协议**，它只保证字节按顺序、不丢失地到达，从来没有承诺过"发送方调用一次 send，接收方就对应收到一次"。至于这些字节被分成几段发送、接收方每次读到多少，TCP 都不关心。

具体到设备接入的场景，以下原因都会导致拆包粘包：

- **发送端合并**：TCP 的 Nagle 算法会把多个小数据包攒在一起发送；设备的 4G 模块也经常会缓存一段时间的数据再一起发出去
- **网络分段**：一个 TCP 报文段的大小有上限（MSS，通常在 1400 字节左右），超过就会被拆开。1078 视频协议的一帧画面动辄几十 KB，必定会被拆成很多段
- **接收端读取**：服务端每次从内核缓冲区读取的，是"当前缓冲区里的所有数据"。如果处理稍慢，缓冲区里就可能已经堆积了好几条报文
- **网络抖动**：移动网络信号差时，数据可能一会儿没有、一会儿一次来一大堆

**结论：区分报文的边界，是应用层协议自己的责任。** 这也是为什么每个协议都要定义帧格式，比如 808 的首尾 7E。

## 解决思路：先缓存，凑够一帧再处理

思路其实很简单：

1. 把收到的数据先放进一个缓冲区
2. 检查缓冲区里有没有一条完整的报文
3. 有，就切出来交给后面处理，然后继续检查（可能还有第二条）
4. 没有，就什么也不做，等下一次数据到达

这个"切报文"的组件叫**帧解码器**。好消息是，Netty 已经帮我们处理好了第 1 步（数据的累积），我们只需要继承 `ByteToMessageDecoder`，在 `decode` 方法里写第 2、3 步的逻辑。

```java
public class MyFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // in 是累积下来的所有数据
        // 如果能切出完整的一帧，就放进 out
        // 如果数据不够，直接 return，Netty 会在下次收到数据后再调用这个方法
    }
}
```

## 常见的三种帧格式

怎么判断"一帧在哪里结束"，取决于协议的设计，常见的有三种：

**① 分隔符**：用特殊字节标记帧的边界，比如 JT/T 808：

```
7E [ 内容（转义后）] 7E
```

**② 长度字段**：帧头后面跟一个长度字段，根据长度确定帧的结束位置。大部分私有协议和 JT/T 1078 都是这种：

```
[ 帧头 ][ ... ][ 长度 ][ 数据（长度个字节）][ 校验 ]
```

**③ 文本长度**：长度用十进制的 ASCII 数字表示，比如 HJ212：

```
##0101QN=...&&1C80\r\n
```

Netty 也提供了几个现成的帧解码器：`FixedLengthFrameDecoder`（定长）、`LineBasedFrameDecoder`（按换行）、`DelimiterBasedFrameDecoder`（按分隔符）、`LengthFieldBasedFrameDecoder`（按长度字段）。

那为什么不直接用它们呢？

## 为什么不直接用 Netty 自带的解码器

以 808 为例，用 `DelimiterBasedFrameDecoder` 会遇到几个问题：

1. **它假设每帧只有末尾一个分隔符**，而 808 首尾各有一个 7E。两条报文连在一起时是 `...7E 7E...`，会切出很多空帧
2. **它不处理转义**，切出来之后还要自己再处理一遍
3. **它遇到超长帧会抛异常**

`LengthFieldBasedFrameDecoder` 用于私有协议时也有一个隐患：**一旦数据错位，就再也回不到正轨**。比如连接刚建立时收到了半条残缺的报文，它会把残缺数据中的某两个字节当成长度字段，后面的解析就全乱了，直到连接断开。

真实的设备网络环境里，这些情况都会发生。所以我在项目里针对这三种帧格式分别写了解码器，下面以 808 为例看看实现。

## 808 帧解码器的实现

完整代码在仓库的 `iot-codec-core` 模块中，这里只看核心逻辑：

```java
@Override
protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // 1. 丢弃第一个 7E 之前的无效数据
    if (!skipToMarker(ctx, in, delimiter)) {
        return;
    }
    int start = in.readerIndex();
    // 2. 从下一个字节开始找结束的 7E
    int end = in.indexOf(start + 1, in.writerIndex(), delimiter[0]);

    if (end < 0) {
        // 结束标识还没到，数据又已经超长，说明这一帧废了
        if (in.readableBytes() > maxFrameLength) {
            discard(ctx, in, in.readableBytes(), DiscardReason.TOO_LONG);
        }
        return;  // 否则继续等数据
    }
    if (end == start + 1) {
        // 连续两个 7E：前一个是上一帧的结尾，从后一个开始算新的一帧
        in.skipBytes(1);
        return;
    }

    // 3. 还原转义，切出一帧
    ByteBuf frame = ctx.alloc().buffer(end - start - 1);
    if (!escaper.unescape(in, start + 1, end - start - 1, frame)) {
        frame.release();
        discard(ctx, in, end - start, DiscardReason.BAD_ESCAPE);
        return;
    }
    in.readerIndex(end + 1);
    out.add(frame);
}
```

几个值得注意的设计：

**`decode` 每次只切一帧。** 切出一帧后直接返回，Netty 发现缓冲区里还有数据，会继续调用 `decode`，直到切不出完整的帧为止。粘包的情况就是这样处理的。

**数据不够时什么都不做。** 直接 `return`，`readerIndex` 不动。下次数据到达时，Netty 会把新数据追加到后面，再调用一次 `decode`。拆包的情况就是这样处理的。

**异常数据丢弃，但不抛异常。** 这一点是我特意这样设计的。Netty 自带的解码器遇到异常数据会抛异常，而抛异常会中断本次的解码循环：**同一次读取中排在坏帧后面的正常报文，要等到这个连接下次收到数据时才会被处理**。对于几分钟才发一次心跳的设备，这意味着一条正常的数据可能被延迟好几分钟。所以这里选择丢弃坏数据、打印日志，然后继续解析。

## 怎么测试：不需要真的建立连接

拆包粘包这种问题，如果要靠真实设备和网络去复现，几乎不可能做到。Netty 提供了 `EmbeddedChannel`，可以在单元测试里模拟数据的到达方式：

```java
@Test
void escapeSplitAcrossReads() {
    // 最容易出 bug 的情况：转义序列 7D 02 被拆到了两次读取里
    writeHex(ch, "7E 01 7D");
    assertTrue(readHex(ch).isEmpty());       // 数据不完整，不应该切出任何帧

    writeHex(ch, "02 03 7E");
    assertEquals(Collections.singletonList("017E03"), readHex(ch));
}

@Test
void badFrameDoesNotBlockNextFrame() {
    // 7D 05 是非法转义。坏帧被丢弃后，同一次读取中的下一帧要立刻解析出来
    writeHex(ch, "7E 01 7D05 7E 7E 02 7E");
    assertEquals(Collections.singletonList("02"), readHex(ch));
}
```

仓库里针对三种帧解码器一共写了 30 个测试用例，覆盖了：完整一帧、多帧粘在一起、一帧被拆开、帧头被拆开、转义序列被拆开、帧前面有垃圾数据、长度字段异常、帧尾不匹配、超长帧等情况。**建议你 clone 下来跑一跑，再试着改坏某一行代码，看看哪个测试会失败**，这是理解拆包粘包最快的方式。

## 在 Netty 中使用

```java
ByteEscaper escaper = new ByteEscaper((byte) 0x7D,
        new byte[]{0x7D, 0x7E},     // 需要转义的字节
        new byte[]{0x01, 0x02});    // 转义后跟在 7D 后面的字节

bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new EscapedDelimiterFrameDecoder((byte) 0x7E, escaper, 2048))
                .addLast(new Jt808Handler());  // 到这里，收到的一定是一条完整的、已还原转义的报文
    }
});
```

注意：帧解码器是**有状态的**（它内部保存着累积的数据），每条连接必须使用独立的实例，所以要在 `initChannel` 里 `new`，不能多条连接共用一个。

## 小结

- 拆包粘包不是 TCP 的 bug，TCP 本来就是字节流，**区分报文边界是应用层自己的事**
- 解决方法是帧解码器：缓存数据，凑够一帧再处理
- 常见的帧格式有三种：分隔符、长度字段、文本长度
- 真实的设备网络环境比本地测试复杂得多，帧解码器要能处理垃圾数据、数据错位，并且一条坏数据不能影响后面的正常数据
- 用 `EmbeddedChannel` 可以在单元测试中模拟各种拆包粘包情况

到这里，入门系列的第一部分就结束了。我们从"物联网平台是什么"讲到了"怎么把一条报文从字节流里完整地切出来"。下一部分，我们会从零开始，真正把一台（模拟的）808 终端接入到自己写的平台上。

---

完整代码：[iot-protocol-gateway](https://github.com/xiaowu-iot/iot-protocol-gateway) 的 `iot-codec-core` 模块
