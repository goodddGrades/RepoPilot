package com.repopilot.common.reliability.mapper;

import com.repopilot.common.reliability.entity.OutboxEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code outbox_events} 的读写。
 *
 * <p>插入语句显式列出列，生成列不参与写入。
 *
 * <p><b>发布协议的核心约束</b>（见 {@code docs/architecture/06-reliability-design.md}）：
 * <ul>
 *   <li>领取是**短事务**：{@code FOR UPDATE SKIP LOCKED} 抢一行、改租约、立刻提交，
 *       绝不能持锁等网络往返；</li>
 *   <li>确认回写必须**同时匹配 claimTokenHash 与有效租约**——失效的发布器
 *       不能把新领取者正在处理的事件标成已发布；</li>
 *   <li>状态只能 PENDING → PUBLISHED / FAILED，不允许倒退。</li>
 * </ul>
 */
@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload_json, status, retry_count, next_retry_at, publisher_id, lease_until, claim_token_hash, published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{aggregateType},
                   #{aggregateId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{eventType},
                   #{eventVersion},
                   #{payloadJson},
                   #{status},
                   #{retryCount},
                   #{nextRetryAt},
                   #{publisherId},
                   #{leaseUntil},
                   #{claimTokenHash},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(OutboxEvent entity);

    @Select("""
            SELECT id,
                   aggregate_type,
                   aggregate_id,
                   event_type,
                   event_version,
                   payload_json,
                   status,
                   retry_count,
                   next_retry_at,
                   publisher_id,
                   lease_until,
                   claim_token_hash,
                   published_at,
                   created_at,
                   updated_at
              FROM outbox_events
             WHERE id = #{id}
            """)
    Optional<OutboxEvent> findById(@Param("id") String id);

    /**
     * 可发布的候选：PENDING 且已到重试时间，或租约已过期的在途事件。
     *
     * <p>租约过期仍然纳入候选，是发布器崩溃后的恢复通道：发布器在"改租约"之后、
     * "标 PUBLISHED"之前挂掉，事件会卡在租约里直到过期，然后被重新领取。
     * 没有这条路径，一次发布器崩溃就会永久丢一条通知。
     *
     * <p>{@code retry_count < maxAttempts} 是有限重试的硬边界：超过就不再尝试，
     * 由扫描器置 FAILED 并告警，而不是无限重投。
     */
    @Select("""
            <script>
            SELECT id
              FROM outbox_events
             WHERE status = 'PENDING'
               AND retry_count &lt; #{maxAttempts}
               AND ((next_retry_at IS NULL AND lease_until IS NULL)
                    OR (next_retry_at IS NOT NULL AND next_retry_at &lt;= #{now} AND lease_until IS NULL)
                    OR (lease_until IS NOT NULL AND lease_until &lt;= #{now}))
             ORDER BY created_at ASC, id ASC
             LIMIT #{limit}
            </script>
            """)
    List<String> listPublishableIds(@Param("now") Instant now,
                                    @Param("maxAttempts") int maxAttempts,
                                    @Param("limit") int limit);

    /**
     * 锁定一个候选并**重新校验**领取条件。
     *
     * <p>{@code SKIP LOCKED} 保证同一事件只会被一个发布器抢到；
     * 条件里重新校验租约，避免"候选查询之后被别人抢走"导致两个发布器同时发布。
     *
     * <p>{@code <script>} 不是可选的：没有它，{@code &lt;} 不会被解码成 {@code <}，
     * 而是把字面量 {@code &lt;} 发给 MySQL —— 运行期报语法错误，编译期完全正常。
     */
    @Select("""
            <script>
            SELECT id,
                   aggregate_type,
                   aggregate_id,
                   event_type,
                   event_version,
                   payload_json,
                   status,
                   retry_count,
                   next_retry_at,
                   publisher_id,
                   lease_until,
                   claim_token_hash,
                   published_at,
                   created_at,
                   updated_at
              FROM outbox_events
             WHERE id = #{id}
               AND status = 'PENDING'
               AND retry_count &lt; #{maxAttempts}
               AND ((lease_until IS NULL)
                    OR (lease_until IS NOT NULL AND lease_until &lt;= #{now}))
             FOR UPDATE SKIP LOCKED
            </script>
            """)
    Optional<OutboxEvent> lockPublishable(@Param("id") String id,
                                          @Param("now") Instant now,
                                          @Param("maxAttempts") int maxAttempts);

    /**
     * 领取：挂上发布器与租约，清空 next_retry_at（已被租约接管）。
     *
     * <p>租约到期时间由调用方算好后传入：退避策略在服务层，不在 SQL 里。
     *
     * <p><b>{@code <script>} 包裹不是可选的</b>：{@code @Update} 里写 {@code &lt;} 时，
     * 没有 {@code <script>} 就不会做 XML 实体解码，字面量 {@code &lt;} 会被原样发给
     * MySQL，报 "syntax error near ';='" ——而编译期完全正常。只有写动态 SQL
     * （{@code <if>}/{@code <where>}）或含转义实体的语句才需要它。
     */
    @Update("""
            <script>
            UPDATE outbox_events
               SET publisher_id = #{publisherId},
                   lease_until = #{leaseUntil},
                   claim_token_hash = #{claimTokenHash},
                   next_retry_at = NULL,
                   updated_at = #{now}
             WHERE id = #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND status = 'PENDING'
               AND ((lease_until IS NULL)
                    OR (lease_until IS NOT NULL AND lease_until &lt;= #{now}))
            </script>
            """)
    int claimForPublish(@Param("id") String id,
                        @Param("publisherId") String publisherId,
                        @Param("leaseUntil") Instant leaseUntil,
                        @Param("claimTokenHash") String claimTokenHash,
                        @Param("now") Instant now);

    /**
     * 确认已发布：broker confirm + mandatory 路由检查都通过后才调用。
     *
     * <p><b>fencing 在 WHERE 里</b>：{@code claim_token_hash} 必须等于本次领取写入的摘要，
     * 且租约未过期。租约过期后失效的发布器回来写确认，必须拿到 0 行——
     * 否则它会把新领取者正在处理的事件误标成 PUBLISHED，导致该事件**永久不再重发**。
     *
     * @return 1 表示确认被接受；0 表示已被重新领取或租约过期（必须丢弃本次确认）
     */
    @Update("""
            <script>
            UPDATE outbox_events
               SET status = 'PUBLISHED',
                   published_at = #{now},
                   lease_until = NULL,
                   publisher_id = NULL,
                   claim_token_hash = NULL,
                   updated_at = #{now}
             WHERE id = #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND status = 'PENDING'
               AND claim_token_hash = #{claimTokenHash}
               AND lease_until IS NOT NULL
               AND lease_until &gt; #{now}
            </script>
            """)
    int markPublished(@Param("id") String id,
                      @Param("claimTokenHash") String claimTokenHash,
                      @Param("now") Instant now);

    /**
     * 发布失败：安排退避重试，仍在 PENDING。
     *
     * <p>租约与令牌一并清空，下一轮任何发布器都能重新领取。
     * 重试次数超限由调用方改调 {@link #markFailedPermanently}。
     */
    @Update("""
            UPDATE outbox_events
               SET retry_count = retry_count + 1,
                   next_retry_at = #{nextRetryAt},
                   lease_until = NULL,
                   publisher_id = NULL,
                   claim_token_hash = NULL,
                   updated_at = #{now}
             WHERE id = #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND status = 'PENDING'
               AND claim_token_hash = #{claimTokenHash}
            """)
    int scheduleRetry(@Param("id") String id,
                      @Param("claimTokenHash") String claimTokenHash,
                      @Param("nextRetryAt") Instant nextRetryAt,
                      @Param("now") Instant now);

    /**
     * 超过重试上限：置 FAILED 并释放租约，等待人工重新投递。
     *
     * <p>与 {@link #scheduleRetry} 一样带 fencing：失效发布器不能把别人正在重试的
     * 事件判死——那会让一个本可成功的事件永远停在 FAILED。
     */
    @Update("""
            UPDATE outbox_events
               SET status = 'FAILED',
                   retry_count = retry_count + 1,
                   lease_until = NULL,
                   publisher_id = NULL,
                   claim_token_hash = NULL,
                   updated_at = #{now}
             WHERE id = #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND status = 'PENDING'
               AND claim_token_hash = #{claimTokenHash}
            """)
    int markFailedPermanently(@Param("id") String id,
                              @Param("claimTokenHash") String claimTokenHash,
                              @Param("now") Instant now);

    /**
     * 人工重新投递：FAILED → PENDING，<b>保留同一个 event_id</b> 并累加发布次数。
     *
     * <p>保留 event_id 是关键：消费者按 (consumer_name, event_id) 去重，
     * 换 ID 会让同一条业务事实被当成两次通知，触发两次副作用。
     * 重置 {@code retry_count} 是因为计数统计的是"上一轮投递尝试了几次"，
     * 人工介入意味着新一轮从零开始。
     */
    @Update("""
            UPDATE outbox_events
               SET status = 'PENDING',
                   retry_count = 0,
                   next_retry_at = NULL,
                   lease_until = NULL,
                   publisher_id = NULL,
                   claim_token_hash = NULL,
                   published_at = NULL,
                   updated_at = #{now}
             WHERE id = #{id,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND status = 'FAILED'
            """)
    int requeueFailed(@Param("id") String id, @Param("now") Instant now);

    // ---- 运维查询 ----

    @Select("""
            <script>
            SELECT status, COUNT(*) AS total
              FROM outbox_events
             <where>
               <if test="status != null">AND status = #{status}</if>
             </where>
             GROUP BY status
            </script>
            """)
    List<StatusCount> countByStatus(@Param("status") String status);

    /** 超期未发布的 PENDING 事件数：MQ 不可用或发布器停摆时的告警依据。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM outbox_events
             WHERE status = 'PENDING' AND created_at &lt; #{before}
            </script>
            """)
    int countStalePending(@Param("before") Instant before);

    /** PENDING 事件的最老创建时间：用于"积压多久了"的人可读告警。 */
    @Select("""
            SELECT MIN(created_at) FROM outbox_events WHERE status = 'PENDING'
            """)
    Optional<Instant> oldestPendingCreatedAt();

    class StatusCount {
        public String status;
        public long total;
    }
}
