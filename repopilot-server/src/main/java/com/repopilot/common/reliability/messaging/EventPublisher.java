package com.repopilot.common.reliability.messaging;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 消息投递的抽象边界。
 *
 * <p><b>为什么要抽这一层</b>：业务可靠性（Outbox 状态机、fencing、重试、退避）
 * 与"消息怎么送到 broker"是两件事。把 RabbitMQ 的 confirm/return 语义藏在
 * 一个接口后面，才能做到：
 * <ul>
 *   <li>故障演练时用假实现精确注入"confirm 丢失""被退回""broker 不可用"，
 *       而不是靠真的把 RabbitMQ 弄挂——后者不可重复、且会让 CI 不稳定；</li>
 *   <li>MQ 完全不可用时，数据库轮询扫描仍能继续工作（TASK-011 的核心验收项之一）。</li>
 * </ul>
 */
public interface EventPublisher {

    /**
     * 投递一条消息并等待 broker 确认。
     *
     * <p>实现必须使用持久化消息、durable 队列、publisher confirms 与
     * mandatory 路由。缺任何一项都会让"确认成功"变成一句空话。
     */
    PublishOutcome publish(String routingKey, JsonNode envelope);

    /** 实现类标识，写进 Outbox 的 publisher_id 与日志。 */
    String publisherId();

    /** 当前是否可用。不可用时发布器应跳过本轮，把退避交给数据库。 */
    boolean available();
}
