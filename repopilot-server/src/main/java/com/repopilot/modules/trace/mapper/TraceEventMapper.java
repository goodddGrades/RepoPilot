package com.repopilot.modules.trace.mapper;

import com.repopilot.modules.trace.entity.TraceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code trace_events} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface TraceEventMapper {

    @Insert("""
            INSERT INTO trace_events (id, event_id, run_id, run_attempt_id, source_event_id, event_hash, trace_id, span_id, parent_span_id, sequence_no, event_type, occurred_at, payload_json, artifact_id, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{eventId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{sourceEventId},
                   #{eventHash},
                   #{traceId},
                   #{spanId},
                   #{parentSpanId},
                   #{sequenceNo},
                   #{eventType},
                   #{occurredAt},
                   #{payloadJson},
                   #{artifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{createdAt})
            """)
    int insert(TraceEvent entity);

    @Select("""
            SELECT id,
                   event_id,
                   run_id,
                   run_attempt_id,
                   source_event_id,
                   event_hash,
                   trace_id,
                   span_id,
                   parent_span_id,
                   sequence_no,
                   event_type,
                   occurred_at,
                   payload_json,
                   artifact_id,
                   created_at
              FROM trace_events
              WHERE id = #{id}
            """)
    Optional<TraceEvent> findById(@Param("id") String id);

    // ---- TASK-004：事件幂等与清单摘要 ----

    /**
     * 批量按 eventId 读已存在事件。
     *
     * <p><b>刻意不加 {@code FOR UPDATE}</b>（TASK-012 压测实测后改）。原来这里锁行是为了
     * 堵住"查询与插入之间的窗口"，但实测证明它制造了更严重的问题：
     *
     * <p>InnoDB 在 REPEATABLE READ 下，{@code WHERE event_id IN (…)} 对<em>尚不存在</em>的行
     * 会加<b>间隙锁</b>。事件 eventId 是 ULID——单调递增、同一毫秒内生成的前缀几乎相同，
     * 于是并发 Worker 的 eventId 在唯一索引 {@code uk_trace_events_event_id} 上必然相邻，
     * 各自持有的间隙互相重叠：
     *
     * <pre>
     * TRX 1: HOLDS  supremum 间隙 X 锁 → 等 insert intention
     * TRX 2: HOLDS  同上           → 等 insert intention
     * </pre>
     *
     * 实测 8 并发 × 60 Run × 40 事件下这是<b>必然</b>触发的死锁，不是低概率事件。
     *
     * <p><b>不加锁如何仍然正确</b>：幂等的真正保证是 {@code uk_trace_events_event_id}
     * 唯一约束，而不是这把读锁。两个请求同时"查不到"又同时插入时，
     * 必然有一个撞唯一键——而撞唯一键的语义本来就必须被处理：
     * 同内容重放返回 DUPLICATE，异内容返回 RESOURCE_CONFLICT。
     * {@code WorkService.appendEvents} 按 eventId 捕获唯一键冲突并重新查询判定，
     * 于是"查不到就插、插不上就回查"就是完整的幂等实现，不需要读锁参与。
     */
    @Select("""
            <script>
            SELECT id,
                   event_id,
                   run_id,
                   run_attempt_id,
                   source_event_id,
                   event_hash,
                   trace_id,
                   span_id,
                   parent_span_id,
                   sequence_no,
                   event_type,
                   occurred_at,
                   payload_json,
                   artifact_id,
                   created_at
              FROM trace_events
             WHERE event_id IN
             <foreach collection="eventIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
            </script>
            """)
    List<TraceEvent> findByEventIds(@Param("eventIds") List<String> eventIds);

    @Select("""
            SELECT event_id
              FROM trace_events
             WHERE run_attempt_id = #{attemptId}
             ORDER BY event_id ASC
            """)
    List<String> listEventIdsByAttempt(@Param("attemptId") String attemptId);

    /**
     * 某 Run 的全部事件 id（TASK-007 评测清单用）。
     *
     * <p>离线 Run 没有 Attempt，事件也没有 {@code run_attempt_id}；在线 Run 的评测输入
     * 必须只取 accepted Attempt 的事件，因此调用方应优先用 {@link #listEventIdsByAttempt}。
     * 这个方法只用于"该 Run 没有 Attempt 语义"的离线场景。
     */
    @Select("""
            SELECT event_id
              FROM trace_events
             WHERE run_id = #{runId}
             ORDER BY event_id ASC
            """)
    List<String> listEventIdsByRun(@Param("runId") String runId);

    /**
     * 证据引用校验：该 eventId 是否真的属于这个 Run。
     *
     * <p>不返回事件内容，只回答"存不存在"。评测结果里的证据必须能被追溯到具体事件，
     * 但跨 Run 引用一律拒绝——否则一个 Run 的结果可以"引用"另一个 Run 的轨迹。
     */
    @Select("""
            SELECT COUNT(*)
              FROM trace_events
             WHERE event_id = #{eventId}
               AND run_id = #{runId}
            """)
    long countEventInRun(@Param("eventId") String eventId, @Param("runId") String runId);

    @Select("SELECT COUNT(*) FROM trace_events WHERE run_attempt_id = #{attemptId}")
    long countByAttempt(@Param("attemptId") String attemptId);

    /**
     * 取一页事件（按 Run），<b>已废弃，请改用 {@link #listPage} 的 keyset 游标</b>。
     *
     * @deprecated 保留仅为对比说明，删除于 TASK-012：无任何调用方。
     * {@code LIMIT ? OFFSET ?} 在深翻页时必须先把 offset 之前的行全部读出再丢弃，
     * 扫描量随页码线性增长；而 Trace 查询的真实访问路径是签名游标
     * （{@link #listPage}），它按键集定位、恒定扫描量。
     * 留着它等于留一个"看起来能用、用了就退化"的入口。
     */
    @Deprecated(since = "TASK-012")
    @Select("""
            <script>
            SELECT id,
                   event_id,
                   run_id,
                   run_attempt_id,
                   source_event_id,
                   event_hash,
                   trace_id,
                   span_id,
                   parent_span_id,
                   sequence_no,
                   event_type,
                   occurred_at,
                   payload_json,
                   artifact_id,
                   created_at
              FROM trace_events
             WHERE run_id = #{runId}
            <if test="attemptId != null"> AND run_attempt_id = #{attemptId} </if>
             ORDER BY occurred_at ASC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TraceEvent> listByRun(@Param("runId") String runId,
                               @Param("attemptId") String attemptId,
                               @Param("limit") int limit,
                               @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM trace_events
             WHERE run_id = #{runId}
            <if test="attemptId != null"> AND run_attempt_id = #{attemptId} </if>
            </script>
            """)
    long countByRun(@Param("runId") String runId, @Param("attemptId") String attemptId);

    /**
     * 离线导入的事件批量写入。
     *
     * <p>与在线不同，这些事件没有 {@code run_attempt_id}：离线 Run 本来就没有执行尝试，
     * 伪造一条 Attempt 只会让"这次执行真的跑过"变成谎言。
     * 单行最多 500 条事件，按 500 分片即可，不需要更细的切分。
     */
    @Insert("""
            <script>
            INSERT INTO trace_events (id, event_id, run_id, run_attempt_id, source_event_id, event_hash, trace_id, span_id, parent_span_id, sequence_no, event_type, occurred_at, payload_json, artifact_id, created_at)
            VALUES
            <foreach collection="events" item="e" separator=",">
                (#{e.id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                 #{e.eventId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                 #{e.runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                 #{e.runAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                 #{e.sourceEventId},
                 #{e.eventHash},
                 #{e.traceId},
                 #{e.spanId},
                 #{e.parentSpanId},
                 #{e.sequenceNo},
                 #{e.eventType},
                 #{e.occurredAt},
                 #{e.payloadJson},
                 #{e.artifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                 #{e.createdAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("events") List<TraceEvent> events);

    // ---- TASK-006：按 (occurred_at, id) 的键集分页 ----

    /**
     * 取一页事件，严格按键集（keyset）而不是 OFFSET。
     *
     * <p>为什么不用 {@code LIMIT ? OFFSET ?}：深翻页时 MySQL 要先把 offset 之前的所有行读出来再丢弃，
     * 长轨迹会出现"翻到第 20 页越来越慢"。键集分页从游标位置直接起读，
     * 单页代价与页码无关，命中索引 {@code idx_trace_events_run_id_occurred_at_id}。
     *
     * <p>同一毫秒的事件用数据库行 {@code id} 做平局排序：ULID 单调且库内排序规则是 ascii_bin，
     * 比较结果与字符串序一致，翻页不会重复或跳过。
     *
     * <p>{@code occurred_at >= ?} 看着与后面的 OR 重复，却是执行计划的关键：MySQL 8 会把
     * {@code (occurred_at, id) > (?, ?)} 这类行构造器比较退化成只在 {@code run_id} 上做 ref
     * （实测 rows=整条 Run），补一个范围谓词后才有机会走 {@code (run_id, occurred_at, id)} 的
     * range（实测 rows≈一页）。语义不变——真正的游标比较仍由 OR 部分完成。
     *
     * <p>游标时间刻意以 {@link LocalDateTime}（UTC 墙钟）而不是 {@link java.sql.Timestamp} 绑定：
     * 同一个值、同一条语句，实测绑 Timestamp 时优化器仍退回 {@code run_id} 的 ref 扫描
     * （深页要把游标之前的索引项全扫一遍），绑 LocalDateTime 才稳定走 range。
     * 库里 {@code occurred_at} 本来就是 UTC 墙钟（写入侧同样按 UTC 落库），两者语义等价。
     *
     * @param afterAt 游标时间（UTC 墙钟）；为 null 表示第一页
     * @param afterId 游标行 id，仅在 afterAt 非空时使用
     * @param limit   调用方传入的是 {@code pageSize + 1}，多取一行用于判断 hasMore
     */
    @Select("""
            <script>
            SELECT id,
                   event_id,
                   run_id,
                   run_attempt_id,
                   source_event_id,
                   event_hash,
                   trace_id,
                   span_id,
                   parent_span_id,
                   sequence_no,
                   event_type,
                   occurred_at,
                   payload_json,
                   artifact_id,
                   created_at
              FROM trace_events
             WHERE run_id = #{runId}
            <if test="attemptId != null"> AND run_attempt_id = #{attemptId} </if>
            <if test="eventType != null"> AND event_type = #{eventType} </if>
            <if test="afterAt != null">
               AND occurred_at &gt;= #{afterAt}
               AND (occurred_at &gt; #{afterAt} OR (occurred_at = #{afterAt} AND id &gt; #{afterId}))
            </if>
             ORDER BY occurred_at ASC, id ASC
             LIMIT #{limit}
            </script>
            """)
    List<TraceEvent> listPage(@Param("runId") String runId,
                              @Param("attemptId") String attemptId,
                              @Param("eventType") String eventType,
                              @Param("afterAt") java.time.LocalDateTime afterAt,
                              @Param("afterId") String afterId,
                              @Param("limit") int limit);
}
