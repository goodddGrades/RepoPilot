package com.repopilot.common.reliability.messaging;

/**
 * 消息投递的结果。
 *
 * <p><b>关键语义：{@code routed} 与「消费者已处理」完全无关。</b>
 * 它只说明 broker 收下并路由到了队列。消费成功与否由消费者侧的 inbox 与业务状态决定。
 * 把 confirm 当成"任务完成"会导致一个常见误判：日志显示"发布成功"，
 * 于是认为评测一定会跑——但队列可能压根没有消费者，或消费者把消息隔离了。
 *
 * @param routed   broker 已确认且路由到至少一个队列
 * @param returned broker 退回（mandatory 投递但无绑定队列），必须重试
 * @param detail   人可读原因，写进日志与 Outbox 运维视图
 */
public record PublishOutcome(boolean routed, boolean returned, String detail) {

    /** 命名不能与组件同名：record 的静态方法与组件访问器会冲突。 */
    public static PublishOutcome success() {
        return new PublishOutcome(true, false, "已确认并路由");
    }

    /**
     * 被退回：exchange 存在但没有任何队列绑定。
     *
     * <p>这是拓扑配错的典型表现，消息<b>没有</b>丢失（broker 收了），
     * 但没有任何消费者会看到它。不重试就等于静默丢消息。
     */
    public static PublishOutcome returned(String detail) {
        return new PublishOutcome(false, true, "消息被退回（无队列绑定）: " + detail);
    }

    /**
     * 结果不确定：confirm 超时或连接在等待期间断开。
     *
     * <p>broker <b>可能</b>已经收下。按可重试处理，代价是可能重复投递——
     * 由消费者 inbox 去重兜住。把"不确定"当成"失败"并直接判死会丢消息，
     * 当成"成功"则会静默漏消息。
     */
    public static PublishOutcome uncertain(String detail) {
        return new PublishOutcome(false, false, "发布结果不确定: " + detail);
    }

    /** 明确失败（连接拒绝、序列化失败、broker nack）：仍可重试，超过上限置 FAILED。 */
    public static PublishOutcome failed(String detail) {
        return new PublishOutcome(false, false, detail);
    }
}
