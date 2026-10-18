package io.github.xiaowuiot.iot.simulator;

import io.github.xiaowuiot.iot.simulator.hj212.Hj212Command;
import io.github.xiaowuiot.iot.simulator.jt808.Jt808Command;
import io.github.xiaowuiot.iot.simulator.slp.SlpCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 模拟器入口。
 * <pre>
 * java -jar iot-simulator.jar jt808 --host 127.0.0.1 --port 7611 --count 100
 * java -jar iot-simulator.jar jt1078 --sim 13800138000 --channel 1 --file sample.h264
 * java -jar iot-simulator.jar hj212 --mn 88888880000001 --interval 60
 * </pre>
 */
@Command(name = "iot-simulator",
        mixinStandardHelpOptions = true,
        version = "iot-simulator 0.1.0-SNAPSHOT",
        description = "IoT 设备模拟器：模拟 JT/T 808、JT/T 1078、HJ212、SLP 终端",
        subcommands = {
                Jt808Command.class,
                SimulatorApplication.Jt1078.class,
                Hj212Command.class,
                SlpCommand.class
        })
public class SimulatorApplication implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new SimulatorApplication()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * 各子命令先占位，按开发计划逐个实现。
     */
    abstract static class Todo implements Runnable {

        private final String plan;

        Todo(String plan) {
            this.plan = plan;
        }

        @Override
        public void run() {
            System.out.println("还在开发中，计划于 " + plan + " 完成");
        }
    }

    @Command(name = "jt1078", description = "模拟 JT/T 1078 音视频推流")
    static class Jt1078 extends Todo {
        Jt1078() {
            super("W9");
        }
    }

}
