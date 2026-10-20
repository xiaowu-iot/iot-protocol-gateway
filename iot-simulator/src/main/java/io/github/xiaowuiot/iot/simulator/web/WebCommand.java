package io.github.xiaowuiot.iot.simulator.web;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * 网页版 HJ212 调试工具：在浏览器里模拟一台现场机连平台，收发协议里的全部报文。
 * <pre>
 * java -jar iot-simulator.jar web
 * java -jar iot-simulator.jar web --port 8090
 * </pre>
 */
@Command(name = "web", mixinStandardHelpOptions = true, description = "网页版 HJ212 调试工具（模拟现场机）")
public class WebCommand implements Callable<Integer> {

    @Option(names = "--port", defaultValue = "8090", description = "页面端口，默认 ${DEFAULT-VALUE}")
    int port;

    @Option(names = "--bind", defaultValue = "127.0.0.1",
            description = "监听地址，默认 ${DEFAULT-VALUE}（只有本机能打开）。这个工具能替页面连任意地址，开放给局域网要谨慎")
    String bind;

    @Override
    public Integer call() throws Exception {
        ToolServer server = new ToolServer();
        server.start(bind, port);
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            done.countDown();
        }));
        done.await();
        return 0;
    }
}
