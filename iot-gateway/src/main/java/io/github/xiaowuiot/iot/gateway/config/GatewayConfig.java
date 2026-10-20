package io.github.xiaowuiot.iot.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.protocol.codec.session.SessionManager;
import io.github.xiaowuiot.iot.gateway.audit.AuditLog;
import io.github.xiaowuiot.iot.gateway.audit.CommandAuditFilter;
import io.github.xiaowuiot.iot.gateway.audit.ConnectionAuditListener;
import io.github.xiaowuiot.iot.gateway.sink.KafkaSink;
import io.github.xiaowuiot.iot.gateway.sink.LogSink;
import io.github.xiaowuiot.iot.gateway.sink.MessageSink;
import io.github.xiaowuiot.iot.gateway.sink.MessageSinks;
import io.github.xiaowuiot.iot.gateway.sink.WebhookSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Configuration
@EnableConfigurationProperties(IotProperties.class)
public class GatewayConfig {

    /**
     * 所有协议共用一个会话管理器，管理 API 可以统一查询在线设备
     */
    @Bean
    public SessionManager sessionManager(AuditLog audit) {
        SessionManager sessions = new SessionManager();
        sessions.addListener(new ConnectionAuditListener(audit));
        return sessions;
    }

    /**
     * 设备连接记录和指令记录，内存里留最近 iot.audit.max-records 条给接口查，全部写到 iot-audit.log
     */
    @Bean
    public AuditLog auditLog(ObjectMapper mapper, @Value("${iot.audit.max-records:5000}") int maxRecords) {
        return new AuditLog(mapper, maxRecords);
    }

    @Bean
    public FilterRegistrationBean<CommandAuditFilter> commandAuditFilter(AuditLog audit) {
        FilterRegistrationBean<CommandAuditFilter> bean = new FilterRegistrationBean<>(new CommandAuditFilter(audit));
        bean.addUrlPatterns("/api/*");
        // 指令接口是异步的，结果在异步分发里才拿得到
        bean.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC);
        return bean;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${iot.sink.types:log}'.contains('kafka')")
    public KafkaProducer<String, String> kafkaProducer(IotProperties props) {
        IotProperties.Sink.Kafka cfg = props.getSink().getKafka();
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // 等所有副本确认，配合幂等，数据不丢不重
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // 攒 5 毫秒再发，吞吐量能提高好几倍，延迟几乎感觉不到
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, cfg.getMaxBlockMs());
        return new KafkaProducer<>(p);
    }

    @Bean
    public MessageSinks messageSinks(IotProperties props, ObjectMapper mapper, MeterRegistry registry,
                                     ObjectProvider<KafkaProducer<String, String>> kafkaProducer) {
        List<MessageSink> sinks = new ArrayList<>();
        for (String type : props.getSink().getTypes()) {
            switch (type.trim()) {
                case "log" -> sinks.add(new LogSink(mapper));
                case "webhook" -> sinks.add(new WebhookSink(props.getSink().getWebhookUrl(), mapper));
                case "kafka" -> sinks.add(new KafkaSink(kafkaProducer.getObject(), mapper,
                        props.getSink().getKafka().getTopicPrefix()));
                default -> throw new IllegalArgumentException("未知的 sink 类型：" + type);
            }
        }
        return new MessageSinks(sinks, registry);
    }
}
