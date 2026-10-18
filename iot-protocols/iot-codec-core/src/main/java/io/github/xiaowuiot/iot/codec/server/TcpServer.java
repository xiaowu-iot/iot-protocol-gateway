package io.github.xiaowuiot.iot.codec.server;

import io.github.xiaowuiot.iot.codec.stats.IoStats;
import io.github.xiaowuiot.iot.codec.stats.RawMessageRecorder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 所有 TCP 接入服务共用的部分：线程模型、TCP 参数、空闲检测、启停顺序。
 * <p>
 * 每个协议只需要提供"往流水线上加哪些 Handler"，其他的都一样。接第二个协议时，你会发现真正和协议相关的代码，
 * 只有编解码器和业务 Handler 两块。
 */
public class TcpServer {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

    private final String name;
    private final String protocol;
    private final int port;
    private final int idleSeconds;
    private final int workerThreads;
    private final Consumer<ChannelPipeline> pipelineConfigurer;
    private final Runnable beforeStop;
    private final IoStats ioStats = new IoStats();
    private final RawMessageRecorder rawRecorder;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    /**
     * @param name               日志里显示的名字，比如"808 接入服务"
     * @param protocol           协议名，和会话里的协议名一致，比如 jt808
     * @param idleSeconds        读空闲超时，建议设为心跳间隔的 3 倍
     * @param workerThreads      IO 线程数，0 表示 Netty 默认值（CPU 核数 × 2）
     * @param pipelineConfigurer 往流水线上加协议相关的 Handler，空闲检测已经加好了
     * @param beforeStop         停止接收新连接之后、关闭线程池之前执行，一般用来关闭已有连接
     */
    public TcpServer(String name, String protocol, int port, int idleSeconds, int workerThreads,
                     Consumer<ChannelPipeline> pipelineConfigurer, Runnable beforeStop) {
        this.name = name;
        this.protocol = protocol;
        this.rawRecorder = new RawMessageRecorder(protocol, 200, 20000);
        this.port = port;
        this.idleSeconds = idleSeconds;
        this.workerThreads = workerThreads;
        this.pipelineConfigurer = pipelineConfigurer;
        this.beforeStop = beforeStop;
    }

    /**
     * 给一条连接装配流水线。单元测试里用它装到 EmbeddedChannel 上，不需要真的监听端口
     */
    public void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("idle", new IdleStateHandler(idleSeconds, 0, 0, TimeUnit.SECONDS))
                // 统计和原始报文记录放在最前面，看到的是未经任何处理的字节
                .addLast("ioStats", ioStats)
                .addLast("rawRecorder", rawRecorder);
        pipelineConfigurer.accept(pipeline);
    }

    public synchronized void start() throws InterruptedException {
        // Linux 上用 epoll 原生传输：比 JDK 的 NIO 少一层封装，大量连接时 CPU 和 GC 压力都更小
        boolean epoll = Epoll.isAvailable();
        boss = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        worker = epoll ? new EpollEventLoopGroup(workerThreads) : new NioEventLoopGroup(workerThreads);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // 发送缓冲区堆积超过 64 KB 时 isWritable() 变成 false，下发大量数据（比如升级包）时要检查它
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        initPipeline(ch.pipeline());
                    }
                });
        try {
            serverChannel = bootstrap.bind(port).sync().channel();
        } catch (InterruptedException | RuntimeException e) {
            worker.shutdownGracefully();
            boss.shutdownGracefully();
            throw e;
        }
        log.info("{}已启动，端口 {}，空闲超时 {} 秒，{}", name, port, idleSeconds, epoll ? "epoll" : "nio");
    }

    public synchronized void stop() {
        if (serverChannel == null) {
            return;
        }
        // 先停止接收新连接，再关闭已有连接，最后关闭线程池
        serverChannel.close().syncUninterruptibly();
        if (beforeStop != null) {
            beforeStop.run();
        }
        worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        serverChannel = null;
        log.info("{}已停止", name);
    }

    public int getPort() {
        return port;
    }

    public String getProtocol() {
        return protocol;
    }

    public IoStats getIoStats() {
        return ioStats;
    }

    public RawMessageRecorder getRawRecorder() {
        return rawRecorder;
    }
}
