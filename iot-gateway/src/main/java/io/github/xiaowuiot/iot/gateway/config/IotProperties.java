package io.github.xiaowuiot.iot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * application.yml 里 iot 开头的配置。
 */
@ConfigurationProperties(prefix = "iot")
public class IotProperties {

    private Server jt808 = new Server(7611);
    private Server jt1078 = new Server(7612);
    // 212 的分钟数据一般 1~10 分钟一条，空闲超时 15 分钟
    private Server hj212 = new Server(7613, 900);
    private Server slp = new Server(7614);
    private Sink sink = new Sink();

    public static class Server {
        private boolean enabled = true;
        private int port;

        /**
         * 读空闲超时（秒），设为心跳间隔的 3 倍
         */
        private int idleSeconds = 180;

        /**
         * IO 线程数，0 表示 CPU 核数 × 2
         */
        private int workerThreads = 0;

        public Server() {
        }

        Server(int port) {
            this.port = port;
        }

        Server(int port, int idleSeconds) {
            this.port = port;
            this.idleSeconds = idleSeconds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getIdleSeconds() {
            return idleSeconds;
        }

        public void setIdleSeconds(int idleSeconds) {
            this.idleSeconds = idleSeconds;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }
    }

    public static class Sink {

        /**
         * log / webhook / kafka，可以同时开多个
         */
        private List<String> types = new ArrayList<>(Collections.singletonList("log"));
        private String webhookUrl;
        private Kafka kafka = new Kafka();

        public static class Kafka {
            private String bootstrapServers = "localhost:9092";
            private String topicPrefix = "iot.";

            /**
             * producer.send() 最长阻塞多久。它在 IO 线程里调用，不能设大
             */
            private int maxBlockMs = 50;

            public String getBootstrapServers() {
                return bootstrapServers;
            }

            public void setBootstrapServers(String bootstrapServers) {
                this.bootstrapServers = bootstrapServers;
            }

            public String getTopicPrefix() {
                return topicPrefix;
            }

            public void setTopicPrefix(String topicPrefix) {
                this.topicPrefix = topicPrefix;
            }

            public int getMaxBlockMs() {
                return maxBlockMs;
            }

            public void setMaxBlockMs(int maxBlockMs) {
                this.maxBlockMs = maxBlockMs;
            }
        }

        public Kafka getKafka() {
            return kafka;
        }

        public void setKafka(Kafka kafka) {
            this.kafka = kafka;
        }

        public List<String> getTypes() {
            return types;
        }

        public void setTypes(List<String> types) {
            this.types = types;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    public Server getJt808() {
        return jt808;
    }

    public void setJt808(Server jt808) {
        this.jt808 = jt808;
    }

    public Server getJt1078() {
        return jt1078;
    }

    public void setJt1078(Server jt1078) {
        this.jt1078 = jt1078;
    }

    public Server getHj212() {
        return hj212;
    }

    public void setHj212(Server hj212) {
        this.hj212 = hj212;
    }

    public Server getSlp() {
        return slp;
    }

    public void setSlp(Server slp) {
        this.slp = slp;
    }

    public Sink getSink() {
        return sink;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }
}
