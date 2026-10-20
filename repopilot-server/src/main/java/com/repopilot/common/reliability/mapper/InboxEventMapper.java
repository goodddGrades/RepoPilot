package com.repopilot.common.reliability.mapper;

import com.repopilot.common.reliability.entity.InboxEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code inbox_events} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface InboxEventMapper {

    @Insert("""
            INSERT INTO inbox_events (id, consumer_name, event_id, payload_hash, processed_at, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{consumerName},
                   #{eventId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{payloadHash},
                   #{processedAt},
                   #{createdAt})
            """)
    int insert(InboxEvent entity);

    @Select("""
            SELECT id,
                   consumer_name,
                   event_id,
                   payload_hash,
                   processed_at,
                   created_at
              FROM inbox_events
              WHERE id = #{id}
            """)
    Optional<InboxEvent> findById(@Param("id") String id);

    // ---- TASK-011：消费去重 ----

    /**
     * 查同一 {@code (consumer_name, event_id)} 已记录的正文摘要。
     *
     * <p>用来区分两种"第二次见到"：<b>同内容</b>是正常重复（可以静默 ACK），
     * <b>异内容</b>是数据损坏或上游 bug（必须隔离告警）。
     * 二者的运维处置完全相反，所以必须能在 SQL 层查出摘要而不是只问"在不在"。
     */
    @Select("""
            SELECT payload_hash FROM inbox_events
             WHERE consumer_name = #{consumerName}
               AND event_id = #{eventId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    Optional<String> findPayloadHash(@Param("consumerName") String consumerName,
                                      @Param("eventId") String eventId);

    /** 某消费者已处理的事件数：用于判断消息是否真被消费过。 */
    @Select("""
            SELECT COUNT(*) FROM inbox_events WHERE consumer_name = #{consumerName}
            """)
    long countByConsumer(@Param("consumerName") String consumerName);

    // ---- TASK-013-D：保留期清理 ----

    /**
     * 分批删除超过去重窗口的行。
     *
     * <p><b>这张表是幂等闸门，删除窗口必须保守</b>：
     * 记录被删后，同一条消息再投一次会被当作新消息<b>重新执行副作用</b>。
     * 因此保留期由 {@code app.repopilot.retention.inbox-days} 控制，
     * 默认 30 天，远大于 Outbox 重试上限（8 次）× 退避（最长 300s）能覆盖的
     * 时间跨度——也就是说，只要事件还在重投窗口内，去重记录就一定还在。
     *
     * <p>用 {@code created_at} 而非 {@code processed_at}：两者都在同一事务写入、
     * 相差微秒级，用 {@code created_at} 与唯一键的统计口径一致，便于对账。
     *
     * <p>带 {@code LIMIT} 分批，避免一次删光导致长事务与锁竞争。
     */
    @Delete("""
            DELETE FROM inbox_events
             WHERE created_at < #{cutoff}
             LIMIT #{limit}
            """)
    int deleteOlderThan(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);

    /** 某时间点之前的行数：清理测试与运维核对用。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM inbox_events
             <where>
               <if test="before != null">AND created_at &lt; #{before}</if>
             </where>
            </script>
            """)
    long countOlderThan(@Param("before") java.time.Instant before);
}
