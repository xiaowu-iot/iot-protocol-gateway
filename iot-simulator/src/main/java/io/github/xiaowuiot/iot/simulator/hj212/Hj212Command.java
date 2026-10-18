package io.github.xiaowuiot.iot.simulator.hj212;

import io.github.xiaowuiot.iot.simulator.SimulatorStats;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 模拟一批 212 数采仪。
 * <pre>
 * java -jar iot-simulator.jar hj212 --verbose
 * java -jar iot-simulator.jar hj212 --count 100 --interval 30
 * </pre>
 */
@Command(name = "hj212", mixinStandardHelpOptions = true, description = "模拟 HJ212 数采仪")
public class Hj212Command implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Hj212Command.class);

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "平台地址，默认 ${DEFAULT-VALUE}")
    String host;

    @Option(names = "--port", defaultValue = "7613", description = "平台端口，默认 ${DEFAULT-VALUE}")
    int port;

    @Option(names = {"-n", "--count"}, defaultValue = "1", description = "数采仪数量，默认 ${DEFAULT-VALUE}")
    int count;

    @Option(names = "--start-mn", defaultValue = "88888880000001", description = "第一台的 MN，后面的依次加 1")
    String startMn;

    @Option(names = "--pw", defaultValue = "123456", description = "访问密码")
    String password;

    @Option(names = "--interval", defaultValue = "30", description = "实时数据上报间隔（秒），默认 ${DEFAULT-VALUE}")
    int interval;

    @Option(names = "--duration", defaultValue = "0", description = "运行多少秒后退出，0 表示一直运行")
    int duration;

    @Option(names = "--clock-offset", defaultValue = "-95", description = "现场机时钟偏差（秒），非 0 时上线后请求校时，默认 ${DEFAULT-VALUE}")
    long clockOffset;

    @Option(names = "--protocol-version", defaultValue = "2017", description = "协议版本：2017 或 2005")
    String protocolVersion;

    @Option(names = "--extra-factors", description = "多报两个标准里没有的因子 w99001、w88123")
    boolean extraFactors;

    @Option(names = "--drop-requests", defaultValue = "0", description = "前几条平台命令假装没收到，测试平台重发")
    int dropRequests;

    @Option(names = {"-v", "--verbose"}, description = "打印收到的每一条消息")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        InetSocketAddress remote = new InetSocketAddress(host, port);
        SimulatorStats stats = new SimulatorStats();
        List<Hj212Device> devices = new ArrayList<>(count);
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            devices.forEach(Hj212Device::stop);
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            done.countDown();
        }));

        log.info("开始模拟 {} 台 212 数采仪，连接 {}:{}，每 {} 秒上报一次实时数据", count, host, port, interval);
        group.scheduleAtFixedRate(() -> log.info(stats.snapshot()), 10, 10, TimeUnit.SECONDS);
        BigInteger mn = new BigInteger(startMn);
        for (int i = 0; i < count; i++) {
            Hj212Device d = new Hj212Device(mn.add(BigInteger.valueOf(i)).toString(), password, interval,
                    bootstrap, remote, stats, verbose);
            d.setFaults(clockOffset, dropRequests);
            d.setVariant("2005".equals(protocolVersion), extraFactors);
            devices.add(d);
            d.connect();
            // 数采仪数量一般不多，固定每秒 200 台
            if (i % 200 == 199) {
                TimeUnit.SECONDS.sleep(1);
            }
        }
        if (duration > 0) {
            if (!done.await(duration, TimeUnit.SECONDS)) {
                log.info(stats.total());
                System.exit(0);
            }
        } else {
            done.await();
        }
        return 0;
    }
}
