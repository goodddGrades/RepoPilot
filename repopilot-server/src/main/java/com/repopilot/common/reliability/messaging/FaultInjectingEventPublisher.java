package com.repopilot.common.reliability.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.id.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入发布器：TASK-011 集成测试专用。
 *
 * <p><b>为什么不真的把 RabbitMQ 弄挂</b>：故障演练必须可重复。
 * 真停 broker 会有三个副作用——CI 不稳定、恢复时间不确定、
 * 以及无法构造"broker 收到了但确认没回来"这种最难排查也最重要的故障。
 * 用可编程的替身可以在毫秒内精确复现每一种故障，且每次跑结果一致。
 *
 * <p><b>它生产的故障都是真实发生过的故障</b>：
 * {@link PublishOutcome#uncertain} 对应 confirm 丢失，
 * {@link PublishOutcome#returned} 对应拓扑配错导致的无队列绑定，
 * {@code available=false} 对应 broker 宕机或网络分区。
 *
 * <p>只在 {@code repopilot.outbox.publisher=fault-injecting} 下注册，
 * 生产与开发环境不会误加载。
 */
@Component
@Primary
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "fault-injecting")
public class FaultInjectingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectingEventPublisher.class);

    private final Map<String, List<JsonNode>> delivered = new ConcurrentHashMap<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile PublishOutcome outcome = PublishOutcome.success();
    private volatile boolean alwaysOutcome = false;
    private volatile boolean available = true;
    private final String publisherId = "fault-" + Ulid.newId();
    private final ObjectMapper objectMapper;

    public FaultInjectingEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String publisherId() {
        return publisherId;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public PublishOutcome publish(String routingKey, JsonNode envelope) {
        attempts.incrementAndGet();
        if (available) {
            // 无论成功与否都记录"broker 侧收到了什么"：
            // confirm 丢失场景下 broker 确实收到了，这份记录是消费者视角的真相
            delivered.computeIfAbsent(routingKey, k -> new ArrayList<>()).add(envelope.deepCopy());
        }
        PublishOutcome result = outcome;
        log.debug("FaultInjecting 发布 routingKey={} 结果={} 可用={}",
                routingKey, result.detail(), available);
        return result;
    }

    // ---- 测试控制 ----

    /** 设定接下来的发布结果。 */
    public void reset(PublishOutcome next) {
        this.outcome = next;
        this.alwaysOutcome = true;
    }

    /** 让发布器恢复"诚实"：按实际结果返回成功。 */
    public void setAlwaysUncertain(boolean value) {
        this.alwaysOutcome = value;
    }

    public void setAvailable(boolean value) {
        this.available = value;
    }

    /** broker 视角收到的信封：confirm 丢失时它其实收到了，测试要能看到。 */
    public List<JsonNode> deliveredOf(String routingKey) {
        return List.copyOf(delivered.getOrDefault(routingKey, List.of()));
    }

    public int attempts() {
        return attempts.get();
    }

    public String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
