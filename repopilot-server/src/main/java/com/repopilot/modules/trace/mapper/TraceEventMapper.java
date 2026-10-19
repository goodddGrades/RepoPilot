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
}
