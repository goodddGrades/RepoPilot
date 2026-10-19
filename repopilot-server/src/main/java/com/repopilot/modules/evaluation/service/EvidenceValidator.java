package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.modules.trace.service.RunStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 评测证据引用校验。
 *
 * <p>证据的作用是让"这条结论从哪里来"可追溯。契约要求服务端校验引用归属，
 * 而不是相信 Worker 自报：一条 FAIL 如果引用的是别的 Run 的事件，
 * 它在界面上看起来仍然"有证据"，但结论无法复盘。
 *
 * <p>允许的引用类型（其余一律 400）：
 * <ul>
 *   <li>{@code event}：{@code {type,eventId}}，eventId 必须属于本 Run；</li>
 *   <li>{@code artifact}：{@code {type,artifactId}}，附件必须属于本 Run；</li>
 *   <li>{@code casePath}：{@code {type,path}}，样本字段路径（input./expected./metadata. 开头）；</li>
 *   <li>{@code outputPath}：{@code {type,path}}，Run 输出字段路径；</li>
 *   <li>{@code usagePath}：{@code {type,path}}，用量/成本字段路径（usage. 开头）。</li>
 * </ul>
 *
 * <p>路径只做形式校验：它指向的是已封口的样本与输出，不是文件系统路径，
 * 因此不会成为目录穿越的入口；带 {@code ..} 的一律拒绝，避免出现的"路径"看起来像文件系统。
 */
@Service
public class EvidenceValidator {

    private static final int MAX_EVIDENCE_ITEMS = 50;
    private static final Set<String> TYPES = Set.of("event", "artifact", "casePath", "outputPath",
            "usagePath");

    private final RunStore runStore;
    private final ObjectMapper objectMapper;

    public EvidenceValidator(RunStore runStore, ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.objectMapper = objectMapper;
    }

    /**
     * @return 规范化后的证据数组（永不为 null；缺省时返回空数组）
     * @throws BusinessException 任一条引用不合法或不属于该 Run
     */
    public JsonNode validate(JsonNode evidence, String runId) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (evidence == null || evidence.isNull()) {
            return normalized;
        }
        if (!evidence.isArray()) {
            throw BusinessException.field("evidence", "evidence 必须是数组");
        }
        if (evidence.size() > MAX_EVIDENCE_ITEMS) {
            throw BusinessException.field("evidence",
                    "单条结果的证据不能超过 " + MAX_EVIDENCE_ITEMS + " 项");
        }
        List<String> claimedEventIds = new ArrayList<>();
        List<String> claimedArtifactIds = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            JsonNode item = evidence.get(i);
            String where = "evidence[" + i + "]";
            if (item == null || !item.isObject()) {
                throw BusinessException.field(where, "证据项必须是对象");
            }
            String type = text(item, "type");
            if (type == null || !TYPES.contains(type)) {
                throw BusinessException.field(where + ".type",
                        "不支持的证据类型: " + type + "，可选值: event, artifact, casePath, outputPath, usagePath");
            }
            ObjectNode out = objectMapper.createObjectNode();
            out.put("type", type);
            switch (type) {
                case "event" -> {
                    String eventId = requireText(item, where, "eventId");
                    claimedEventIds.add(eventId);
                    out.put("eventId", eventId);
                }
                case "artifact" -> {
                    String artifactId = requireText(item, where, "artifactId");
                    claimedArtifactIds.add(artifactId);
                    out.put("artifactId", artifactId);
                }
                case "casePath" -> {
                    String path = requireText(item, where, "path");
                    requireSafePath(where, path);
                    if (!path.startsWith("input.") && !path.startsWith("expected.")
                            && !path.startsWith("metadata.")) {
                        throw BusinessException.field(where + ".path",
                                "casePath 必须以 input./expected./metadata. 开头: " + path);
                    }
                    out.put("path", path);
                }
                case "usagePath" -> {
                    String path = requireText(item, where, "path");
                    requireSafePath(where, path);
                    if (!path.startsWith("usage.")) {
                        throw BusinessException.field(where + ".path",
                                "usagePath 必须以 usage. 开头: " + path);
                    }
                    out.put("path", path);
                }
                default -> {
                    String path = requireText(item, where, "path");
                    requireSafePath(where, path);
                    out.put("path", path);
                }
            }
            normalized.add(out);
        }
        // 归属校验放在形式校验之后：先把请求本身的问题报清楚，再查库
        for (String eventId : claimedEventIds) {
            if (!runStore.eventBelongsToRun(eventId, runId)) {
                throw BusinessException.field("evidence",
                        "证据引用了不属于本 Run 的事件: " + eventId);
            }
        }
        if (!claimedArtifactIds.isEmpty()) {
            Set<String> owned = runStore.artifactIdsOwnedByRun(runId, claimedArtifactIds);
            for (String artifactId : claimedArtifactIds) {
                if (!owned.contains(artifactId)) {
                    throw BusinessException.field("evidence",
                            "证据引用了不属于本 Run 的附件: " + artifactId);
                }
            }
        }
        return normalized;
    }

    private static String requireText(JsonNode item, String where, String field) {
        String value = text(item, field);
        if (value == null) {
            throw BusinessException.field(where + "." + field, "必须是非空字符串");
        }
        if (value.length() > 256) {
            throw BusinessException.field(where + "." + field, "长度不能超过 256");
        }
        return value;
    }

    private static String text(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    private static void requireSafePath(String where, String path) {
        if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            throw BusinessException.field(where + ".path",
                    "路径必须是字段路径，不能是文件系统路径: " + path);
        }
    }
}
