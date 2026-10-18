package io.github.xiaowuiot.iot.simulator.slp;

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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 模拟一批 SLP 储物柜。
 * <pre>
 * java -jar iot-simulator.jar slp --count 5
 * </pre>
 */
@Command(name = "slp", mixinStandardHelpOptions = true, description = "模拟 SLP 智能储物柜")
public class SlpCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SlpCommand.class);

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "平台地址，默认 ${DEFAULT-VALUE}")
    String host;

    @Option(names = "--port", defaultValue = "7614", description = "平台端口，默认 ${DEFAULT-VALUE}")
    int port;

    @Option(names = {"-n", "--count"}, defaultValue = "1", description = "储物柜数量，默认 ${DEFAULT-VALUE}")
    int count;

    @Option(names = "--start-id", defaultValue = "202610170001", description = "第一台的设备 ID，后面的依次加 1")
    long startId;

    @Option(names = "--doors", defaultValue = "24", description = "每台的格口数，默认 ${DEFAULT-VALUE}")
    int doors;

    @Option(names = "--duration", defaultValue = "0", description = "运行多少秒后退出，0 表示一直运行")
    int duration;

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
        List<SlpDevice> devices = new ArrayList<>(count);
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            devices.forEach(SlpDevice::stop);
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            done.countDown();
        }));

        log.info("开始模拟 {} 台储物柜，连接 {}:{}", count, host, port);
        group.scheduleAtFixedRate(() -> log.info(stats.snapshot()), 10, 10, TimeUnit.SECONDS);
        for (int i = 0; i < count; i++) {
            SlpDevice d = new SlpDevice(startId + i, doors, bootstrap, remote, stats, verbose);
            devices.add(d);
            d.connect();
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
