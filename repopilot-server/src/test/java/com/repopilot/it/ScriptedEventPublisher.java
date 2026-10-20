package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.reliability.messaging.EventPublisher;
import com.repopilot.common.reliability.messaging.PublishOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 测试用的可编程发布器（TASK-013）。
 *
 * <p><b>为什么不直接写匿名类</b>：{@link EventPublisher} 有三个方法，
 * 每个用例只关心 {@code publish} 的行为，另外两个（{@code publisherId}、
 * {@code available}）每次都要重复写同样的桩代码。抽出来之后，
 * "只看 publish"的用例可以一行构造，且断言可以直接查 {@link #calls()}。
 *
 * <p><b>它测的是 Outbox 状态机</b>，不是 RabbitMQ：真正的 broker 行为由
 * {@link RabbitTopologyIT} 用真实 broker 覆盖。两者的分工是——
 * 本类证明"给定发布结果，数据库状态怎么变"，
 * 真实 broker 证明"这些结果确实对应真实的 AMQP 语义"。
 */
final class ScriptedEventPublisher implements EventPublisher {

    private final BiFunction<String, JsonNode, PublishOutcome> script;
    private final boolean available;
    private final List<String> calls = new ArrayList<>();

    private ScriptedEventPublisher(BiFunction<String, JsonNode, PublishOutcome> script,
                                   boolean available) {
        this.script = script;
        this.available = available;
    }

    /** 总是成功。 */
    static ScriptedEventPublisher alwaysSuccess() {
        return new ScriptedEventPublisher((key, body) -> PublishOutcome.success(), true);
    }

    /** 总是失败（模拟连接故障 / 不可路由）。 */
    static ScriptedEventPublisher alwaysFailed(String detail) {
        return new ScriptedEventPublisher((key, body) -> PublishOutcome.failed(detail), true);
    }

    /** 总是被退回（broker 收下但路由不到队列）。 */
    static ScriptedEventPublisher alwaysReturned(String detail) {
        return new ScriptedEventPublisher((key, body) -> PublishOutcome.returned(detail), true);
    }

    /** confirm 超时（不确定，可能已送达）。 */
    static ScriptedEventPublisher alwaysUncertain(String detail) {
        return new ScriptedEventPublisher((key, body) -> PublishOutcome.uncertain(detail), true);
    }

    /** 自定义行为，可按 routingKey 区分。 */
    static ScriptedEventPublisher of(BiFunction<String, JsonNode, PublishOutcome> script) {
        return new ScriptedEventPublisher(script, true);
    }

    /** broker 不可用：scanOnce 应整轮跳过，事件留在 PENDING。 */
    static ScriptedEventPublisher unavailable() {
        return new ScriptedEventPublisher((key, body) -> PublishOutcome.failed("不可用"), false);
    }

    @Override
    public PublishOutcome publish(String routingKey, JsonNode envelope) {
        calls.add(routingKey);
        return script.apply(routingKey, envelope);
    }

    @Override
    public String publisherId() {
        return "scripted-publisher";
    }

    @Override
    public boolean available() {
        return available;
    }

    /** 被调用过的 routing key 列表，供"消息没被投递"类断言使用。 */
    List<String> calls() {
        return calls;
    }
}
