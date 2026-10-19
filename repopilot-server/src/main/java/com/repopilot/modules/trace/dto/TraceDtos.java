package com.repopilot.modules.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.modules.trace.entity.TraceEvent;

import java.time.Instant;
import java.util.List;

/**
 * Trace 查询的对外视图。
 *
 * <p>字段是"回放需要什么就给什么"，不直接把实体序列化出去：实体含 {@code event_hash}、
 * 数据库行 {@code id} 等内部列，其中行 id 只用于排序平局，不承担业务含义。
 *
 * <p>{@code payload} 原样返回、前端必须转义展示。服务端不按"看起来像 HTML"过滤，
 * 否则证据就被改写了；安全边界在渲染端（Vue 文本插值默认转义）。
 */
public final class TraceDtos {

    private TraceDtos() {
    }

    /**
     * 一条规范化轨迹事件。
     *
     * <p>{@code artifactId} 只在该 Artifact 确实归属本 Run 时才返回，否则为 {@code null}：
     * 跨 Run 的附件引用属于脏数据，不能借列表接口把它变成"可访问的证据"。
     */
    public record EventView(String eventId,
                            String attemptId,
                            String eventType,
                            Long sequenceNo,
                            Instant occurredAt,
                            String traceId,
                            String spanId,
                            String parentSpanId,
                            String sourceEventId,
                            String artifactId,
                            JsonNode payload) {

        public static EventView from(TraceEvent entity, String safeArtifactId) {
            return new EventView(entity.getEventId(), entity.getRunAttemptId(),
                    entity.getEventType(), entity.getSequenceNo(), entity.getOccurredAt(),
                    entity.getTraceId(), entity.getSpanId(), entity.getParentSpanId(),
                    entity.getSourceEventId(), safeArtifactId, entity.getPayloadJson());
        }
    }

    /**
     * 本次查询实际使用的读取范围。
     *
     * <p>前端据此解释"为什么没有尝试切换"或"事件为什么没有 Attempt"：
     * 离线轨迹本来就没有执行尝试，把 {@code resolvedFrom=NONE} 显示成"加载失败"是误导。
     *
     * @param resolvedFrom {@code REQUESTED}（显式 attemptId）/ {@code ALL}（allAttempts=true）/
     *                     {@code ACCEPTED}（接受尝试）/ {@code ACTIVE}（执行中）/
     *                     {@code NONE}（没有可用的 Attempt，离线导入即属此类）
     */
    public record EventScope(String attemptId,
                             boolean allAttempts,
                             String eventType,
                             String resolvedFrom) {
    }

    /** 契约响应：{@code {items, nextCursor, hasMore}}；{@code scope} 是附加说明，不替代前三者。 */
    public record EventPage(List<EventView> items,
                            String nextCursor,
                            boolean hasMore,
                            EventScope scope) {
    }
}
