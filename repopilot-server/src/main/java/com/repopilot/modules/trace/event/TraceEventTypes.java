package com.repopilot.modules.trace.event;

import com.repopilot.common.exception.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * 轨迹事件类型注册表（{@code docs/architecture/05-event-contract.md}）。
 *
 * <p>schema 只约束信封，payload 的业务必填字段必须在服务层二次校验——否则一条
 * {@code TOOL_STARTED} 可以不带 toolName 就入库，前端画拓扑时只能显示空白节点。
 *
 * <p>未知类型直接 400，不做"先存下来以后再说"：轨迹是评测证据，
 * 存进去的脏数据会被当成观测结果。
 */
public final class TraceEventTypes {

    private static final Map<String, List<String>> REQUIRED = Map.of(
            "RUN_STARTED", List.of("runnerType"),
            "RUN_COMPLETED", List.of("taskOutcome"),
            "MODEL_REQUEST", List.of("model", "requestSummary"),
            "MODEL_RESPONSE", List.of("model", "outputSummary"),
            "TOOL_STARTED", List.of("toolCallId", "toolName", "argumentsSummary"),
            "TOOL_COMPLETED", List.of("toolCallId", "toolName", "outcome", "durationMs"),
            "OUTPUT_PRODUCED", List.of("outputSummary"),
            "ERROR", List.of("errorCode", "message"));

    private TraceEventTypes() {
    }

    public static boolean isRegistered(String eventType) {
        return eventType != null && REQUIRED.containsKey(eventType);
    }

    public static List<String> requiredFields(String eventType) {
        return REQUIRED.getOrDefault(eventType, List.of());
    }

    public static void require(String eventType, com.fasterxml.jackson.databind.JsonNode payload) {
        if (!isRegistered(eventType)) {
            throw BusinessException.field("events.eventType",
                    "未知的 eventType: " + eventType + "，可选值: " + String.join(", ",
                            REQUIRED.keySet().stream().sorted().toList()));
        }
        if (payload == null || !payload.isObject()) {
            throw BusinessException.field("events.payload", "payload 必须是 JSON 对象");
        }
        for (String field : requiredFields(eventType)) {
            if (!payload.hasNonNull(field)) {
                throw BusinessException.field("events.payload." + field,
                        eventType + " 缺少必填字段 " + field);
            }
        }
    }
}
