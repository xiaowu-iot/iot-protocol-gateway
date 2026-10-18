package io.github.xiaowuiot.iot.gateway.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaowuiot.iot.gateway.message.DeviceMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 打印到日志，默认开启，开发调试用。
 */
public class LogSink implements MessageSink {

    private static final Logger log = LoggerFactory.getLogger("iot.data");

    private final ObjectMapper mapper;

    public LogSink(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public CompletableFuture<Void> send(DeviceMessage message) {
        if (log.isInfoEnabled()) {
            try {
                log.info(mapper.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                log.info("{}", message);
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
