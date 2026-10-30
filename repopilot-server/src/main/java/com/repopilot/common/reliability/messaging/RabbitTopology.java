package com.repopilot.common.reliability.messaging;

/**
 * RabbitMQ 拓扑的<b>单一来源</b>（TASK-013）。
 *
 * <p>TASK-011 只定义了事件信封，没声明任何拓扑，于是发布器把消息发到
 * default exchange（{@code exchange=""}）+ 路由键 {@code run.ready}，
 * broker 上没有同名队列，该消息被退回，日志里留下
 * {@code NOT_FOUND - no exchange 'run.ready'}。更糟的是：如果发布器当时没开
 * {@code mandatory}，这次失败会<b>完全静默</b>——日志显示"发布成功"，
 * 而没有任何消费者会收到。契约 {@code docs/architecture/05-event-contract.md}
 * 早就写明了 {@code repopilot.events} topic 交换机，是实现没跟上契约。
 *
 * <p><b>为什么用常量类而不是 Spring AMQP 的声明式配置</b>：
 * {@code @Configuration} + {@code Declarables} 需要 Spring 上下文，
 * 而集成测试要能在"用故障注入替身"和"用真实 broker"两种模式下复用同一份定义。
 * 纯常量让 {@link RabbitTopologyDeclarer} 在两种模式下都能工作，
 * 测试也能直接断言"声明了什么"。
 *
 * <p><b>四层拓扑与各自的职责</b>：
 * <pre>
 *   发布者 ──► repopilot.events（业务交换机，topic）
 *                 │  binding: run.ready / run.finished / evaluation.ready / experiment.finished
 *                 ▼
 *             业务队列（quorum, durable, DLX→dlx）
 *                 │  消费失败且可重试 → 消费者主动 publish 到 repopilot.events.retry.v2
 *                 │  routing key = retry.tN
 *                 ▼
 *   消费者 ──► repopilot.events.retry.v2（重试交换机，topic）
 *                 │  binding: retry.t1 / retry.t2 / retry.t3（精确键，无通配符）
 *                 ▼
 *             重试等待队列 repopilot.retry.wait.tN（quorum, per-queue TTL, DLX→republish）
 *                 │  TTL 到期死信到 repopilot.events.republish，routing key 固定为 retry.republish
 *                 ▼
 *             再发布队列 repopilot.retry.republish（无 TTL，由 Worker 消费）
 *                 │  Worker 读 x-original-routing-key，重新 publish 到 repopilot.events
 *                 ▼
 *             回到业务队列（重新投递，routing key 复原为原值）
 *
 *   消费失败且重试耗尽 → basic_reject(requeue=false)
 *                 ▼
 *             repopilot.events.dlx ──► repopilot.events.dlq（死信队列，quorum, durable）
 * </pre>
 *
 * <p><b>队列语义的关键决定</b>：
 * <ul>
 *   <li><b>durable + 消息 persistent</b>：broker 重启后队列和消息都还在。
 *       少任何一半，"已发布"就意味着"可能已丢"。</li>
 *   <li><b>manual ACK</b>：Worker 处理完才 ack。
 *       auto-ack 会在消息刚投递时就确认，Worker 崩溃即丢消息——
 *       而任务还在 QUEUED 状态，看起来一切正常，实际永远不会执行。</li>
 *   <li><b>重试靠"带 TTL 的重试队列"，不靠 {@code basic_nack(requeue=true)}</b>：
 *       requeue 会把消息立刻放回队首，形成"取出→失败→立刻取回"的<b>热循环</b>，
 *       期间一条消息能占住整个消费者。带 TTL 的重试队列让消息<b>留在 broker 里等</b>，
 *       消费者可以立刻处理下一条，退避时长由队列参数声明、跨进程一致。</li>
 *   <li><b>DLQ 与业务交换机物理隔离</b>：死信不可能被当成业务消息消费。
 *       业务交换机上没有任何"死信 routing key"的绑定。</li>
 * </ul>
 */
public final class RabbitTopology {

    private RabbitTopology() {
    }

    /** 业务事件 topic 交换机：发布者只发到这里，由 binding 分发到各队列。 */
    public static final String EVENTS_EXCHANGE = "repopilot.events";

    /**
     * 死信交换机：与业务交换机<b>分开</b>。
     *
     * <p>分开的理由：如果 DLX 和业务交换机是同一个，运维时"误把 DLQ 绑回业务交换机"
     * 就会立刻造成死信消息回流到业务队列。物理隔离让这种误操作在声明层就不可能。
     */
    public static final String DLX_EXCHANGE = "repopilot.events.dlx";

    // ---------------------------------------------------------------------
    // 退避重试：topic 而非 headers（TASK-013 实测结论）
    // ---------------------------------------------------------------------

    /**
     * 重试交换机（<b>topic</b> 类型）。
     *
     * <p><b>为什么是 topic 而不是 headers</b>：第一版用
     * {@code headers} + {@code x-match=all, x-retry-tier=N} 绑定分档，
     * 在真实 RabbitMQ 3.13.7 上<b>实测失效</b>——
     * {@code x-match=all} 会<b>忽略 {@code x-} 前缀的绑定字段</b>，
     * 于是 {@code x-retry-tier} 从未参与匹配，所有消息都进了<b>每一个</b>档位队列。
     * 后果是"3 档指数退避"退化为"全部并发回流"：
     * 第一档 5 秒的 TTL 决定了实际等待，而 2/3 档形同虚设。
     *
     * <p>实测范围（结论写入 {@code docs/reviews/task-013-amqp-facts.md}）：
     * Java 客户端与 pika 两种客户端、默认 vhost 与全新 vhost、
     * {@code rabbitmqctl list_bindings} 确认 binding 参数已正确登记，
     * 均复现"tier=99 的消息进入 t1 队列"。
     * RabbitMQ 官方提供 {@code all-with-x} 作为"也匹配 x- 前缀字段"的取值，
     * 但三级重试的路由语义用 topic 表达更直白，也更容易在运维工具里肉眼核对。
     *
     * <p><b>为什么不原地改类型</b>：{@code exchange.declare} 对已存在的
     * 交换机改类型会抛 {@code PRECONDITION_FAILED} 并关掉 channel，
     * 而滚动发布期间新旧实例并存会让启动直接失败。
     * 所以启用<b>新名字</b> {@link #RETRY_EXCHANGE_V2}，旧的
     * {@link #RETRY_EXCHANGE_LEGACY} 保持原样但不再使用。
     */
    public static final String RETRY_EXCHANGE = "repopilot.events.retry.v2";

    /**
     * 上一版使用的 headers 重试交换机（<b>已废弃，不再使用</b>）。
     *
     * <p>保留常量是为了让迁移工具能<b>识别并清理</b>它，
     * 而不是让代码里悄悄多出一条"看起来还在用"的路径。
     */
    public static final String RETRY_EXCHANGE_LEGACY = "repopilot.events.retry";

    /**
     * 再发布交换机：TTL 到期后的消息先到这里，由 Worker 重新投递回业务交换机。
     *
     * <p><b>为什么需要这一层</b>：topic 方案下重试消息的 routing key 是
     * {@code retry.t1} 这样的<b>合成键</b>，而死信回流时<b>沿用的正是这个合成键</b>，
     * 业务交换机上没有它的 binding，消息会被静默丢弃。
     *
     * <p>两个可选方案：
     * <ol>
     *   <li><b>按事件类型展开队列</b>：3 档 × 4 事件类型 = 12 个等待队列，
     *       每���用 {@code x-dead-letter-routing-key} 固定回流目标。
     *       问题：队列数随事件类型线性增长，新增事件类型必须改代码加队列，
     *       而"队列数与事件类型解耦"正是第一版headers 方案的全部动机。</li>
     *   <li><b>本实现：加一层"再发布"</b>：等待队列 TTL 到期后统一死信到
     *       {@link #REPUBLISH_EXCHANGE}，Worker 消费再发布队列，
     *       读消息头 {@link #HEADER_ORIGINAL_ROUTING_KEY} 复原 routing key
     *       后重新投递到业务交换机。
     *       代价是消息多一次 broker 内部转发 + 一次 Worker 处理；
     *       换来的是<b>等待队列恒为 3 个</b>，与事件类型数量无关。</li>
     * </ol>
     *
     * <p><b>再发布必须走 confirm</b>：重新投递成功后才 ACK 等待队列里的消息。
     * 顺序反了会在"发布成功但 ACK 前崩溃"时丢消息。
     */
    public static final String REPUBLISH_EXCHANGE = "repopilot.events.republish";

    /** 再发布队列：TTL 到期后的消息落在这里，等Worker 重新投递。 */
    public static final String REPUBLISH_QUEUE = "repopilot.retry.republish";

    /** 再发布 routing key：等待队列的 DLX 把它作为死信 routing key。 */
    public static final String RK_REPUBLISH = "retry.republish";

    /** 重试 routing key 前缀：{@code retry.t1} / {@code retry.t2} / {@code retry.t3}。 */
    public static final String RETRY_KEY_PREFIX = "retry.t";

    /**
     * 消息头：当前处于第几档重试（0 = 首次投递，未重试过）。
     *
     * <p><b>仍然保留这个头</b>，但它<b>不再参与路由决策</b>——
     * 路由由 {@code retry.tN} routing key 决定。这个头只用于：
     * 诊断（DLQ 摘要里显示"已重试几档"）与断言（测试校验档位正确递增）。
     * 把它和路由决策解耦，是为了避免"头与队列不一致"这种
     * 无法从 broker 侧察觉的静默错误。
     */
    public static final String HEADER_RETRY_TIER = "x-retry-tier";

    /**
     * 消息头：<b>原始业务 routing key</b>（如 {@code run.ready}）。
     *
     * <p>再发布阶段靠它把消息送回正确的业务队列。
     * 缺失时无法复原 routing key，只能退回 DLQ——
     * 猜一个 routing key 等于把消息投给错误的消费者。
     */
    public static final String HEADER_ORIGINAL_ROUTING_KEY = "x-original-routing-key";

    /** 消息头：首次失败的 ISO 时间戳。 */
    public static final String HEADER_FIRST_FAILED_AT = "x-first-failed-at";

    /** 消息头：最近一次失败的原因（截断后，不含载荷）。 */
    public static final String HEADER_LAST_ERROR = "x-last-error";

    /** 执行类事件队列（{@code run.ready} / {@code run.finished}）。 */
    public static final String RUN_QUEUE = "repopilot.run.ready";

    /** 评测类事件队列（{@code evaluation.ready}）。 */
    public static final String EVALUATION_QUEUE = "repopilot.evaluation.ready";

    /** 实验收口事件队列（{@code experiment.finished}）：无消费者也要留存，供排障与重放。 */
    public static final String EXPERIMENT_QUEUE = "repopilot.experiment.finished";

    /** 死信队列：最终无法处理的消息，保留原始信封供人工排查与受控重放。 */
    public static final String DEAD_LETTER_QUEUE = "repopilot.events.dlq";

    /** routing key 就是 eventType——契约里{@code EventEnvelope} 的 eventType 枚举值。 */
    public static final String RK_RUN_READY = "run.ready";
    public static final String RK_EVALUATION_READY = "evaluation.ready";
    public static final String RK_RUN_FINISHED = "run.finished";
    public static final String RK_EXPERIMENT_FINISHED = "experiment.finished";

    /**
     * DLQ 的 routing key <b>就是原来的 routing key</b>（不加工前缀）。
     *
     * <p><b>为什么不用 {@code dead.} 前缀</b>：AMQP 的
     * {@code basic.reject}/{@code basic.nack} <b>无法指定死信 routing key</b>
     * ——死信消息沿用原消息的 routing key（改写只能靠
     * {@code basic.publish} 的 {@code CC} 头，而 reject 路径没有这个能力）。
     * 所以只要队列设了 {@code x-dead-letter-exchange}，死信就必然以
     * {@code run.ready} 这样的原始 key 到达 DLX。
     *
     * <p>于是 DLQ 只能绑定<b>全部</b>业务 routing key。若用 {@code dead.*} 绑定，
     * 死信会被 DLX 收到但<b>匹配不到任何队列</b>，然后被 broker 直接丢弃——
     * 表现是"日志显示已拒绝、DLQ 里什么都没有"，这是最难排查的一类静默故障。
     * 本实现第一版就是这样写的，实测死信全部消失。
     */
    public static final String DLQ_PREFIX = "";

    /** DLQ 需要绑定的全部 routing key（死信沿用原 key）。 */
    public static final java.util.List<String> DEAD_LETTER_KEYS = java.util.List.of(
            RK_RUN_READY, RK_EVALUATION_READY, RK_RUN_FINISHED, RK_EXPERIMENT_FINISHED);

    // ---------------------------------------------------------------------
    // 分层退避重试（TASK-013-C）
    // ---------------------------------------------------------------------

    /**
     * 退避档位（毫秒），索引 0 = 第一次重试。
     *
     * <p><b>为什么是"固定档位"而不是"每次现算"</b>：退避时长声明在
     * <b>队列参数</b>（{@code x-message-ttl}）里，跨进程一致。
     * 消费者只需要知道"当前是第几档"，不需要知道每档多少秒——
     * 于是 Java 侧与 Python 侧不会因为"退避公式写得不一致"而行为分叉。
     *
     * <p><b>为什么每档一个队列而不是用 per-message TTL</b>：per-message TTL
     * 会造成<b>队头阻塞</b>——队头消息未到期时，其后的消息即使已到期也不能投递
     * （RabbitMQ 对单队列的 per-message TTL 只检查队头）。
     * 后果是"一条 5 分钟的重试把 5 秒重试的消息一起压住"。
     * per-queue TTL 没有这个问题。
     *
     * <p>数值是<b>有上限</b>的：最慢一档 120 秒。任务书要求"可重试用有上限
     * 指数退避"——档位用尽后消息进 DLQ，不会永远在队列里转圈。
     */
    public static final java.util.List<Long> RETRY_BACKOFF_MILLIS = java.util.List.of(
            5_000L, 30_000L, 120_000L);

    /** 重试档位数：{@code RETRY_BACKOFF_MILLIS.size()}。 */
    public static final int RETRY_TIER_COUNT = RETRY_BACKOFF_MILLIS.size();

    /**
     * 等待队列名：{@code repopilot.retry.wait.t<档位>}。
     *
     * <p><b>与事件类型无关</b>：靠 {@code retry.tN} routing key 分流，
     * 所以无论将来新增多少种事件类型，等待队列始终只有这 3 个。
     */
    public static String retryQueueName(int tier) {
        return "repopilot.retry.wait.t" + tier;
    }

    /** 重试 routing key：{@code retry.t<档位>}（如 {@code retry.t1}）。 */
    public static String retryRoutingKey(int tier) {
        return RETRY_KEY_PREFIX + tier;
    }

    /**
     * 该 routing key 是否是一条合法的重试 routing key。
     *
     * <p>用于拒绝非法档位：{@code retry.t0}（等于首次投递，没有退避语义）
     * 与 {@code retry.t4}（超出档位数）都<b>不该</b>被投递——
     * 前者会让失败立刻回流形成热循环，后者没有对应队列会被静默丢弃。
     *
     * @return 1..{@link #RETRY_TIER_COUNT} 时返回 true
     */
    public static boolean isValidRetryTier(int tier) {
        return tier >= 1 && tier <= RETRY_TIER_COUNT;
    }

    /**
     * 解析 routing key 里的档位：{@code retry.t2} → 2。不是重试键时返回 0。
     *
     * <p>用于再发布阶段判断"这条消息是不是从等待队列过来的"，
     * 以及在测试与运维工具里核对档位。
     */
    public static int retryTierOfRoutingKey(String routingKey) {
        if (routingKey == null || !routingKey.startsWith(RETRY_KEY_PREFIX)) {
            return 0;
        }
        String suffix = routingKey.substring(RETRY_KEY_PREFIX.length());
        if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        try {
            int tier = Integer.parseInt(suffix);
            return isValidRetryTier(tier) ? tier : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 解析消息头 {@code x-retry-tier}：当前处于第几档重试。
     *
     * <p>首次投递没有这个头，视为 0（还没重试过）。
     * 消费者用它决定"下一档该投到哪个队列"，超过 {@link #RETRY_TIER_COUNT}
     * 就说明退避已经用尽，消息该进 DLQ。
     */
    public static int retryTierOf(java.util.Map<String, Object> headers) {
        if (headers == null) {
            return 0;
        }
        Object raw = headers.get(HEADER_RETRY_TIER);
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public static String deadLetterRoutingKey(String originalRoutingKey) {
        // 原样返回：AMQP 的 reject 路径无法改写死信 routing key。
        return originalRoutingKey;
    }

    /**
     * 该事件类型应该投递到哪个业务队列。
     *
     * <p>{@code run.finished} 与 {@code run.ready} 共用执行队列：
     * Worker 关心的是"有活干了"，两种事件都指向 Run 聚合。
     */
    public static String queueFor(String eventType) {
        return switch (eventType) {
            case EventEnvelopeTypes.RUN_READY, EventEnvelopeTypes.RUN_FINISHED -> RUN_QUEUE;
            case EventEnvelopeTypes.EVALUATION_READY -> EVALUATION_QUEUE;
            case EventEnvelopeTypes.EXPERIMENT_FINISHED -> EXPERIMENT_QUEUE;
            default -> null;
        };
    }

    /**
     * 事件类型常量（与 {@code event-envelope.schema.json} 的 enum 逐一对应）。
     *
     * <p>刻意不在这里依赖 {@code EventEnvelope}：那个类属于事件信封语义，
     * 而这里是 AMQP 拓扑命名；两者会有意保持一致但不应互相依赖，
     * 否则改信封结构就会牵动拓扑。
     */
    public static final class EventEnvelopeTypes {
        private EventEnvelopeTypes() {
        }

        public static final String RUN_READY = "run.ready";
        public static final String EVALUATION_READY = "evaluation.ready";
        public static final String RUN_FINISHED = "run.finished";
        public static final String EXPERIMENT_FINISHED = "experiment.finished";
    }
}