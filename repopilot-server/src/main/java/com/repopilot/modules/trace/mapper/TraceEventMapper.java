package com.repopilot.modules.trace.mapper;

import com.repopilot.modules.trace.entity.TraceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
