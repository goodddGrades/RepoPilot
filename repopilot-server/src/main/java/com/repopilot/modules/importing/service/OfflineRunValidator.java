package com.repopilot.modules.importing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.state.TaskOutcome;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 离线轨迹行校验与规范化（唯一一份规则实现）。
 *
 * <p>严格对齐 {@code docs/contracts/offline-run.schema.json}：{@code additionalProperties=false}，
 * 未知字段即错。这一点是刻意的——如果对拼写错误宽容，{@code sourceRecordID} 这种笔误会让
 * 整条记录的幂等身份变成另一条数据，而且很长时间没人会发现。
 *
 * <p>两条摘要刻意分开，用途不同：
 * <ul>
 *   <li>{@code lineHash}：<b>原始行字节</b>（去行末 CR/LF）的 SHA-256，用于错误追溯与回执；</li>
 *   <li>{@code businessHash}：规范化业务 JSON 的摘要，用于"同来源同正文复用"。</li>
 * </ul>
 * 混用会让"缩进变了"和"内容变了"变得无法区分。
 *
 * <p>时间戳必须带时区：{@code OffsetDateTime.parse} 对没有偏移量的字符串直接失败，
 * 正是契约要的行为（{@code 2026-10-17T04:00:00} 被拒绝，而不是猜一个时区出来）。
 *
 * <p>校验器是无状态单例：每次调用创建自己的 {@link Ctx} 保存"第一条错误"，
 * 因此可以被并发调用，也不需要 ThreadLocal。
 */
@Component
public class OfflineRunValidator {

    /** {@code offline-run.schema.json} 的顶层允许字段。 */
    private static final Set<String> TOP_FIELDS = Set.of(
            "schemaVersion", "targetKey", "caseKey", "repeatIndex", "sourceSystem", "sourceRecordId",
            "taskOutcome", "traceId", "externalTraceRef", "output", "usage", "observationMetadata",
            "startedAt", "finishedAt", "events");

    private static final Set<String> EVENT_FIELDS = Set.of(
            "sourceEventId", "traceId", "spanId", "parentSpanId", "sequenceNo", "eventType",
            "occurredAt", "payload");

    private static final Set<String> OBSERVATION_FIELDS = Set.of(
            "outputKnown", "traceCoverage", "outcomeSource", "adapterVersion");

    private static final Set<String> EVENT_TYPES = Set.of(
            "RUN_STARTED", "RUN_COMPLETED", "MODEL_REQUEST", "MODEL_RESPONSE",
            "TOOL_STARTED", "TOOL_COMPLETED", "OUTPUT_PRODUCED", "ERROR");

    private static final Set<String> TASK_OUTCOMES =
            Set.of("UNKNOWN", "SUCCESS", "FAILURE", "NOT_APPLICABLE");

    private static final Set<String> TRACE_COVERAGE = Set.of("NONE", "PARTIAL", "COMPLETE");

    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32}$)[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^(?!0{16}$)[0-9a-f]{16}$");

    private static final int MAX_EVENTS = 500;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public OfflineRunValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Event(String sourceEventId, String traceId, String spanId, String parentSpanId,
                        Long sequenceNo, String eventType, OffsetDateTime occurredAt, JsonNode payload) {
    }

    public record Valid(int lineNo,
                        String lineHash,
                        String businessHash,
                        String targetKey,
                        String caseKey,
                        int repeatIndex,
                        String sourceSystem,
                        String sourceRecordId,
                        TaskOutcome taskOutcome,
                        String traceId,
                        String externalTraceRef,
                        JsonNode output,
                        JsonNode usage,
                        JsonNode observationMetadata,
                        OffsetDateTime startedAt,
                        OffsetDateTime finishedAt,
                        List<Event> events) {
    }

    public record LineError(int lineNo, String field, String errorCode, String message) {
    }

    public record Outcome(Valid value, LineError error) {
    }

    /** 解析并校验一行原始文本。 */
    public Outcome validate(int lineNo, String text) {
        JsonNode node;
        try {
            node = CanonicalJson.readTree(text);
        } catch (Exception ex) {
            return new Outcome(null, new LineError(lineNo, base(lineNo), "INVALID_JSON",
                    "该行不是合法 JSON"));
        }
        return validateNode(lineNo, text, node);
    }

    public Outcome validateNode(int lineNo, String text, JsonNode node) {
        Ctx ctx = new Ctx(lineNo);
        if (node == null || !node.isObject()) {
            return ctx.fail("", "SCHEMA_VIOLATION", "该行必须是 JSON 对象");
        }
        String unknown = firstUnknown(node, TOP_FIELDS);
        if (unknown != null) {
            return ctx.fail("." + unknown, "SCHEMA_VIOLATION", "存在未知字段: " + unknown);
        }

        JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion == null || schemaVersion.isNull()) {
            return ctx.fail(".schemaVersion", "SCHEMA_VIOLATION", "缺少 schemaVersion");
        }
        if (!schemaVersion.isIntegralNumber() || schemaVersion.asInt() != 1) {
            return ctx.fail(".schemaVersion", "UNSUPPORTED_SCHEMA_VERSION", "schemaVersion 只支持 1");
        }

        String targetKey = ctx.text(node, "targetKey", 64, true);
        String caseKey = ctx.text(node, "caseKey", 128, true);
        String sourceSystem = ctx.text(node, "sourceSystem", 64, true);
        String sourceRecordId = ctx.text(node, "sourceRecordId", 128, true);
        Integer repeatIndex = ctx.positiveInt(node, "repeatIndex");
        String taskOutcomeText = ctx.text(node, "taskOutcome", 32, true);
        String traceId = ctx.hex(node, "traceId", TRACE_ID, 32);
        String externalTraceRef = ctx.text(node, "externalTraceRef", 256, false);
        OffsetDateTime startedAt = ctx.time(node, "startedAt");
        OffsetDateTime finishedAt = ctx.time(node, "finishedAt");
        if (ctx.failed()) {
            return ctx.outcome();
        }

        if (!TASK_OUTCOMES.contains(taskOutcomeText)) {
            return ctx.fail(".taskOutcome", "SCHEMA_VIOLATION",
                    "taskOutcome 取值非法: " + taskOutcomeText);
        }
        if (startedAt != null && finishedAt != null
                && finishedAt.toInstant().isBefore(startedAt.toInstant())) {
            return ctx.fail(".finishedAt", "INVALID_TIME_RANGE", "finishedAt 不能早于 startedAt");
        }

        JsonNode usage = node.get("usage");
        if (usage != null && !usage.isNull() && !usage.isObject()) {
            return ctx.fail(".usage", "SCHEMA_VIOLATION", "usage 必须是 JSON 对象或 null");
        }
        JsonNode observation = node.get("observationMetadata");
        if (observation != null && !observation.isNull()) {
            if (!observation.isObject()) {
                return ctx.fail(".observationMetadata", "SCHEMA_VIOLATION",
                        "observationMetadata 必须是 JSON 对象");
            }
            String badField = firstUnknown(observation, OBSERVATION_FIELDS);
            if (badField != null) {
                return ctx.fail(".observationMetadata." + badField, "SCHEMA_VIOLATION",
                        "存在未知字段: " + badField);
            }
            JsonNode outputKnown = observation.get("outputKnown");
            if (outputKnown != null && !outputKnown.isNull() && !outputKnown.isBoolean()) {
                return ctx.fail(".observationMetadata.outputKnown", "SCHEMA_VIOLATION",
                        "outputKnown 必须是布尔值");
            }
            JsonNode coverage = observation.get("traceCoverage");
            if (coverage != null && !coverage.isNull()
                    && (!coverage.isTextual() || !TRACE_COVERAGE.contains(coverage.asText()))) {
                return ctx.fail(".observationMetadata.traceCoverage", "SCHEMA_VIOLATION",
                        "traceCoverage 取值非法");
            }
            // 只做长度与类型校验；返回值不回填，观测元数据整体原样入库
            ctx.textAt(observation, "outcomeSource", 128, false, ".observationMetadata");
            ctx.textAt(observation, "adapterVersion", 128, false, ".observationMetadata");
            if (ctx.failed()) {
                return ctx.outcome();
            }
        }

        JsonNode eventsNode = node.get("events");
        if (eventsNode == null || eventsNode.isNull() || !eventsNode.isArray()) {
            return ctx.fail(".events", "SCHEMA_VIOLATION", "events 必须是数组");
        }
        if (eventsNode.size() > MAX_EVENTS) {
            return ctx.fail(".events", "SCHEMA_VIOLATION",
                    "events 最多 " + MAX_EVENTS + " 条，当前 " + eventsNode.size() + " 条");
        }
        List<Event> events = new ArrayList<>(eventsNode.size());
        for (int i = 0; i < eventsNode.size(); i++) {
            Event event = validateEvent(ctx, eventsNode.get(i), ".events[" + i + "]");
            if (ctx.failed()) {
                return ctx.outcome();
            }
            events.add(event);
        }

        Valid valid = new Valid(lineNo,
                Sha256.ofUtf8(text),
                businessHash(node),
                targetKey, caseKey, repeatIndex, sourceSystem, sourceRecordId,
                TaskOutcome.valueOf(taskOutcomeText),
                traceId, blankToNull(externalTraceRef),
                node.get("output"),
                usage == null || usage.isNull() ? null : usage,
                observation == null || observation.isNull() ? null : observation,
                startedAt, finishedAt,
                List.copyOf(events));
        return new Outcome(valid, null);
    }

    private Event validateEvent(Ctx ctx, JsonNode node, String prefix) {
        if (node == null || !node.isObject()) {
            ctx.fail(prefix, "SCHEMA_VIOLATION", "事件必须是 JSON 对象");
            return null;
        }
        String unknown = firstUnknown(node, EVENT_FIELDS);
        if (unknown != null) {
            ctx.fail(prefix + "." + unknown, "SCHEMA_VIOLATION", "存在未知字段: " + unknown);
            return null;
        }
        String eventType = ctx.textAt(node, "eventType", 32, true, prefix);
        if (ctx.failed()) {
            return null;
        }
        if (!EVENT_TYPES.contains(eventType)) {
            ctx.fail(prefix + ".eventType", "SCHEMA_VIOLATION", "未知 eventType: " + eventType);
            return null;
        }
        JsonNode payload = node.get("payload");
        if (payload == null || payload.isNull() || !payload.isObject()) {
            ctx.fail(prefix + ".payload", "SCHEMA_VIOLATION", "payload 必须是 JSON 对象");
            return null;
        }
        if (CanonicalJson.canonicalBytes(payload).length > MAX_PAYLOAD_BYTES) {
            ctx.fail(prefix + ".payload", "SCHEMA_VIOLATION",
                    "payload 超过 " + (MAX_PAYLOAD_BYTES / 1024) + " KiB");
            return null;
        }
        JsonNode occurred = node.get("occurredAt");
        if (occurred == null || occurred.isNull() || !occurred.isTextual()) {
            ctx.fail(prefix + ".occurredAt", "SCHEMA_VIOLATION", "缺少 occurredAt");
            return null;
        }
        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(occurred.asText());
        } catch (DateTimeParseException ex) {
            ctx.fail(prefix + ".occurredAt", "INVALID_TIMESTAMP",
                    "occurredAt 必须是带时区的 ISO-8601 时间");
            return null;
        }

        String sourceEventId = ctx.textAt(node, "sourceEventId", 128, false, prefix);
        String eventTraceId = ctx.hexAt(node, "traceId", TRACE_ID, 32, prefix);
        String spanId = ctx.hexAt(node, "spanId", SPAN_ID, 16, prefix);
        String parentSpanId = ctx.hexAt(node, "parentSpanId", SPAN_ID, 16, prefix);
        Long sequenceNo = ctx.nonNegativeLong(node, "sequenceNo", prefix);
        if (ctx.failed()) {
            return null;
        }
        return new Event(blankToNull(sourceEventId), eventTraceId, spanId, parentSpanId,
                sequenceNo, eventType, occurredAt, payload);
    }

    /**
     * 语义幂等摘要：只包含业务内容，不包含行号与文件位置。
     *
     * <p>逐字段显式构造，而不是直接对整行求摘要——后者会把"字段顺序不同"当成异正文。
     */
    private String businessHash(JsonNode node) {
        ObjectNode canonical = objectMapper.createObjectNode();
        for (String name : List.of("targetKey", "caseKey", "repeatIndex", "sourceSystem",
                "sourceRecordId", "taskOutcome", "traceId", "externalTraceRef", "output",
                "usage", "observationMetadata", "startedAt", "finishedAt", "events")) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                canonical.set(name, value);
            }
        }
        return Sha256.ofUtf8(CanonicalJson.canonicalize(canonical));
    }

    private static String firstUnknown(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private static String base(int lineNo) {
        return "lines[" + lineNo + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 单次校验的上下文：保存行号与"第一条错误"。
     *
     * <p>只记第一条：一次返回全部错误在行级层面没有意义——同一行的后续错误往往是
     * 第一个错误的连锁反应（字段缺失导致类型判断也失败），全列出来只会淹没真正的原因。
     */
    private final class Ctx {

        private final int lineNo;
        private LineError error;

        Ctx(int lineNo) {
            this.lineNo = lineNo;
        }

        boolean failed() {
            return error != null;
        }

        Outcome fail(String suffix, String code, String message) {
            if (error == null) {
                error = new LineError(lineNo, base(lineNo) + suffix, code, message);
            }
            return outcome();
        }

        Outcome outcome() {
            return new Outcome(null, error);
        }

        String text(JsonNode node, String name, int maxLength, boolean required) {
            return textAt(node, name, maxLength, required, "");
        }

        String textAt(JsonNode node, String name, int maxLength, boolean required, String prefix) {
            JsonNode value = node.get(name);
            String path = prefix.isEmpty() ? "." + name : prefix + "." + name;
            if (value == null || value.isNull()) {
                if (required) {
                    fail(path, "SCHEMA_VIOLATION", "缺少 " + name);
                }
                return null;
            }
            if (!value.isTextual()) {
                fail(path, "SCHEMA_VIOLATION", name + " 必须是字符串");
                return null;
            }
            String text = value.asText();
            if (required && text.isBlank()) {
                fail(path, "SCHEMA_VIOLATION", name + " 必须是非空字符串");
                return null;
            }
            if (text.length() > maxLength) {
                fail(path, "SCHEMA_VIOLATION", name + " 超过 " + maxLength + " 字符");
                return null;
            }
            return text;
        }

        /** 返回 {@code null} 表示字段缺失；非法值会置错并返回 null。 */
        String hex(JsonNode node, String name, Pattern pattern, int length) {
            return hexAt(node, name, pattern, length, "");
        }

        String hexAt(JsonNode node, String name, Pattern pattern, int length, String prefix) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                return null;
            }
            String path = prefix.isEmpty() ? "." + name : prefix + "." + name;
            if (!value.isTextual() || !pattern.matcher(value.asText()).matches()) {
                fail(path, "SCHEMA_VIOLATION",
                        name + " 必须是 " + length + " 位小写十六进制且不能全为 0");
                return null;
            }
            return value.asText();
        }

        Integer positiveInt(JsonNode node, String name) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                fail("." + name, "SCHEMA_VIOLATION", "缺少 " + name);
                return null;
            }
            if (!value.isIntegralNumber() || value.asLong() < 1
                    || value.asLong() > Integer.MAX_VALUE) {
                fail("." + name, "SCHEMA_VIOLATION", name + " 必须是 ≥1 的整数");
                return null;
            }
            return value.asInt();
        }

        Long nonNegativeLong(JsonNode node, String name, String prefix) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isIntegralNumber() || value.asLong() < 0) {
                fail(prefix + "." + name, "SCHEMA_VIOLATION", name + " 必须是 ≥0 的整数");
                return null;
            }
            return value.asLong();
        }

        OffsetDateTime time(JsonNode node, String name) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isTextual()) {
                fail("." + name, "INVALID_TIMESTAMP", name + " 必须是字符串");
                return null;
            }
            try {
                return OffsetDateTime.parse(value.asText());
            } catch (DateTimeParseException ex) {
                fail("." + name, "INVALID_TIMESTAMP", name + " 必须是带时区的 ISO-8601 时间");
                return null;
            }
        }
    }
}
