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
     * 批量按 eventId 读已存在事件并加锁。
     *
     * <p>为什么要加锁而不是"先查后插"：同一批事件重放与首次写入可能并发，
     * 查询与插入之间的窗口足够让两边的 eventId 都"不存在"。
     * 锁住已存在的行后，后到者要么看到行（走 DUPLICATE），要么插入撞唯一键（整批 409）。
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
               FOR UPDATE
            </script>
            """)
    List<TraceEvent> findByEventIdsForUpdate(@Param("eventIds") List<String> eventIds);

    @Select("""
            SELECT event_id
              FROM trace_events
             WHERE run_attempt_id = #{attemptId}
             ORDER BY event_id ASC
            """)
    List<String> listEventIdsByAttempt(@Param("attemptId") String attemptId);

    @Select("SELECT COUNT(*) FROM trace_events WHERE run_attempt_id = #{attemptId}")
    long countByAttempt(@Param("attemptId") String attemptId);

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
