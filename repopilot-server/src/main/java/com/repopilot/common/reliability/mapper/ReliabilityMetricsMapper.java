package com.repopilot.common.reliability.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 消息链路的<b>只读观测查询</b>（TASK-013-E）。
 *
 * <p><b>为什么不把这些方法加进各表的 Mapper</b>：它们跨越多张表
 * （outbox × inbox × runs × attempts），语义上属于"链路观测"而不是"某张表的读写"。
 * 放在独立 Mapper 里，运维想找"这条指标从哪来"时只需要看一个文件。
 *
 * <p><b>这些查询全部只读</b>：观测路径绝不能修改业务数据。
 * 一个会写库的"监控"比没有监控更糟——它会在排障时改变被排障的对象。
 *
 * <p><b>刻意不新建表</b>：所有指标都能从既有表算出。
 * 新表会带来迁移、字典同步、生成器一致性三处负担，
 * 而这些数字本来就在库里，存一份只是把"真相"复制到第二处并承担它漂移的风险。
 */
@Mapper
public interface ReliabilityMetricsMapper {

    /**
     * 待处理任务积压：处于 QUEUED、等待 Worker 领取的 Run 数。
     *
     * <p><b>为什么它比"队列深度"更能说明问题</b>：RabbitMQ 队列深度是<b>消息</b>数，
     * 而任务是否真的堆积取决于数据库里有多少 Run 在等。
     * 消息可能被误入 DLQ（队列深度 0 但任务积压），也可能消息重投多次（队列深度高但任务没变多）。
     * 两者必须分别看。
     */
    @Select("""
            SELECT COUNT(*) FROM evaluation_runs WHERE status = 'QUEUED'
            """)
    long countQueuedRuns();

    /**
     * 最老的待处理 Run 已经等了多久（秒）。
     *
     * <p><b>用 {@code created_at} 而不是 {@code updated_at}</b>：
     * {@code updated_at} 会被领取、心跳、状态推进反复刷新，
     * 一个卡住两小时的 Run 可能刚被心跳刷新过——那正是最需要被发现的。
     */
    @Select("""
            SELECT COALESCE(
                     TIMESTAMPDIFF(MICROSECOND,
                       MIN(created_at),
                       NOW(3)) / 1000000.0,
                     0)
              FROM evaluation_runs
             WHERE status = 'QUEUED'
            """)
    double oldestQueuedRunAgeSeconds();

    /**
     * 租约已过期但状态仍是 RUNNING 的执行 Attempt 数。
     *
     * <p><b>这个数持续大于 0 就是告警信号</b>：说明有 Worker 拿了任务就失联了
     * （进程被杀、网络断开、机器宕机），租约到期后没人来收尸。
     * 后台的租约回收器会把它们重置为可重试，但回收之前它们一直占着任务。
     *
     * <p>注意它统计的是"**尚未被回收**"的数量：回收是异步的，
     * 所以这个数下降得慢并不代表回收器坏了。
     */
    @Select("""
            SELECT COUNT(*)
              FROM run_attempts
             WHERE status = 'RUNNING'
               AND lease_until < NOW(3)
            """)
    long countExpiredRunningAttempts();

    /**
     * 消息端到端延迟：从发布者确认发出，到消费者登记进 Inbox，过去了多少秒。
     *
     * <p><b>关联方式</b>：{@code outbox_events.id} 就是事件的 eventId
     * （{@code OutboxWriter} 写库时用同一个 ULID 做主键与信封 eventId），
     * 而 {@code inbox_events.event_id} 记的也是同一个值，所以可以直接 JOIN。
     *
     * <p><b>为什么对 Inbox 侧先去重</b>：Inbox 的唯一键是
     * {@code (consumer_name, event_id)}。同一个事件如果被两个不同的 consumer_name 登记过，
     * 直接 JOIN 会让这一行出现两次，{@code AVG} 于是把它算了两遍——
     * 样本量被悄悄放大，均值失真。去重后每个事件只贡献一次。
     *
     * <p><b>为什么过滤掉负值</b>：重放的消息，其 Inbox 记录是<b>首次</b>消费时写的
     * （重放会命中去重），所以 {@code processed_at} 可能早于
     * {@code published_at}。这属于正常现象（重放路径），不是"负延迟"，
     * 混进平均值会把指标拉成负数，看起来像时钟错乱。
     */
    @Select("""
            SELECT COALESCE(AVG(lag_seconds), 0)
              FROM (SELECT DISTINCT
                           o.id,
                           TIMESTAMPDIFF(MICROSECOND, o.published_at, i.processed_at) / 1000000.0
                             AS lag_seconds
                      FROM (SELECT id, published_at
                              FROM outbox_events
                             WHERE status = 'PUBLISHED'
                               AND published_at IS NOT NULL
                             ORDER BY published_at DESC
                             LIMIT 100) o
                      JOIN (SELECT event_id, MIN(processed_at) AS processed_at
                              FROM inbox_events
                             GROUP BY event_id) i ON i.event_id = o.id
                     WHERE i.processed_at >= o.published_at) d
            """)
    double averageDeliveryLatencySeconds();

    /**
     * 消息端到端延迟的最坏值（最近 100 条已发布事件内）。
     *
     * <p>与平均值并存的原因：平均值会把"绝大多数 1 秒、偶尔 1 小时"这种情况
     * 压成看起来正常的数字，而<b>那一条卡住的消息正是要查的那个</b>。
     */
    @Select("""
            SELECT COALESCE(MAX(lag_seconds), 0)
              FROM (SELECT DISTINCT
                           o.id,
                           TIMESTAMPDIFF(MICROSECOND, o.published_at, i.processed_at) / 1000000.0
                             AS lag_seconds
                      FROM (SELECT id, published_at
                              FROM outbox_events
                             WHERE status = 'PUBLISHED'
                               AND published_at IS NOT NULL
                             ORDER BY published_at DESC
                             LIMIT 100) o
                      JOIN (SELECT event_id, MIN(processed_at) AS processed_at
                              FROM inbox_events
                             GROUP BY event_id) i ON i.event_id = o.id
                     WHERE i.processed_at >= o.published_at) d
            """)
    double maxDeliveryLatencySeconds();
}
