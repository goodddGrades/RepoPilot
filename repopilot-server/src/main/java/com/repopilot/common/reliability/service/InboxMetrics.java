package com.repopilot.common.reliability.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Inbox 消费结果的指标注册（TASK-013-E）。
 *
 * <p><b>为什么单独一个配置类</b>：{@link InboxService} 需要一个
 * {@code Consumer<Outcome>} 来上报结果，但不该自己依赖 Micrometer——
 * 那样它就无法在无registry 的单元测试里直接 {@code new} 出来。
 * 把"计数"这件事放在配置类里，InboxService 只负责上报，业务代码保持干净。
 *
 * <p><b>每种 Outcome 单独计数，而不是一个总数</b>：它们的运维含义完全不同。
 * {@code DUPLICATE} 上涨 = 重复投递变多（可能有消费者在 ACK 前崩溃）；
 * {@code PAYLOAD_CONFLICT} 出现 = 同 eventId 不同内容，<b>数据损坏或上游 bug</b>，
 * 必须立刻有人看。只记总数会把这两件事混成"消费量"。
 */
@Configuration
public class InboxMetrics {

    /**
     * 按 Outcome 计数的观察者。
     *
     * <p>没有出现的 Outcome 也预先注册为 0：这样
     * {@code /actuator/prometheus} 里始终能看到全部指标，
     * 而不是"出问题了才第一次出现"——后者会让人误以为是新问题。
     */
    @Bean
    public java.util.function.Consumer<InboxService.Outcome> inboxOutcomeObserver(
            MeterRegistry registry) {
        Map<InboxService.Outcome, Counter> counters = new EnumMap<>(InboxService.Outcome.class);
        for (InboxService.Outcome outcome : InboxService.Outcome.values()) {
            counters.put(outcome, Counter.builder("repopilot.inbox." + name(outcome))
                    .description("Inbox 消费结果统计：" + outcome)
                    .tag("outcome", outcome.name())
                    .register(registry));
        }
        return outcome -> {
            Counter counter = counters.get(outcome);
            if (counter != null) {
                counter.increment();
            }
        };
    }

    private String name(InboxService.Outcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> "accepted";
            case DUPLICATE -> "duplicate";
            case PAYLOAD_CONFLICT -> "payload.conflict";
            case UNSUPPORTED_VERSION -> "unsupported.version";
            case MALFORMED -> "malformed";
        };
    }
}
