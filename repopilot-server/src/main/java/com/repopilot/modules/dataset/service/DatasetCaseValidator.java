package com.repopilot.modules.dataset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.registry.TaskType;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Set;

/**
 * 样本字段校验：只判断"这些字段能不能变成一条样本"，不碰数据库。
 *
 * <p><b>只有这一份规则实现</b>：JSONL 导入、单条 {@code POST /cases}、{@code PUT /dataset-cases/{id}}
 * 三条入口都经过这里。规则写两遍的后果很具体——导入能通过的数据，单条接口拒绝，
 * 用户会认为是接口不稳定。
 *
 * <p>错误码是契约的一部分（写在 message 首词，便于前端与脚本识别）：
 * {@code INVALID_JSON} / {@code SCHEMA_VIOLATION} / {@code UNSUPPORTED_SCHEMA_VERSION} /
 * {@code UNKNOWN_TASK_TYPE} / {@code INVALID_CASE_KEY}。
 *
 * <p>校验顺序与契约一致：先结构（是不是合法对象、有没有多余字段），再版本号，再业务字段。
 * 顺序固定才能保证同一份坏文件每次得到同一条错误——否则用户改了一处、错误跳到另一处，很难排查。
 */
@Component
public class DatasetCaseValidator {

    /** {@code dataset-case.schema.json} 的全部允许字段（additionalProperties=false）。 */
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("schemaVersion", "caseKey", "taskType", "input", "expected", "metadata");

    private static final int MAX_CASE_KEY_LENGTH = 128;
    private static final int MAX_TASK_TYPE_LENGTH = 32;

    public record LineError(int lineNo, String field, String errorCode, String message) {
    }

    public record Valid(int lineNo, String caseKey, String taskType,
                        JsonNode input, JsonNode expected, JsonNode metadata) {
    }

    /** 二选一：成功给 value，失败给 error。 */
    public record Outcome(Valid value, LineError error) {
    }

    /** 解析并校验一行原始文本。 */
    public Outcome validate(int lineNo, String text) {
        JsonNode node;
        try {
            // 用 CanonicalJson 的解析器：与摘要算法共用同一套数字语义（标度保留）
            node = CanonicalJson.readTree(text);
        } catch (Exception ex) {
            return fail(lineNo, field(lineNo), "INVALID_JSON", "该行不是合法 JSON");
        }
        return validateNode(lineNo, node);
    }

    /** 校验 JSONL 的一行：含 {@code schemaVersion} 与未知字段检查。 */
    public Outcome validateNode(int lineNo, JsonNode node) {
        String field = field(lineNo);
        if (node == null || !node.isObject()) {
            return fail(lineNo, field, "SCHEMA_VIOLATION", "该行必须是 JSON 对象");
        }

        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!ALLOWED_FIELDS.contains(name)) {
                return fail(lineNo, field + "." + name, "SCHEMA_VIOLATION", "存在未知字段: " + name);
            }
        }

        JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion == null || schemaVersion.isNull()) {
            return fail(lineNo, field + ".schemaVersion", "SCHEMA_VIOLATION", "缺少 schemaVersion");
        }
        if (!schemaVersion.isIntegralNumber() || schemaVersion.asInt() != 1) {
            return fail(lineNo, field + ".schemaVersion", "UNSUPPORTED_SCHEMA_VERSION",
                    "schemaVersion 只支持 1");
        }

        String caseKey = textOf(node, "caseKey");
        String taskType = textOf(node, "taskType");

        String problem = caseKeyProblem(caseKey);
        if (problem != null) {
            return fail(lineNo, field + ".caseKey", "INVALID_CASE_KEY", problem);
        }
        problem = taskTypeProblem(taskType);
        if (problem != null) {
            return fail(lineNo, field + ".taskType", "UNKNOWN_TASK_TYPE", problem);
        }
        problem = inputProblem(node.get("input"));
        if (problem != null) {
            return fail(lineNo, field + ".input", "SCHEMA_VIOLATION", problem);
        }
        problem = metadataProblem(node.get("metadata"));
        if (problem != null) {
            return fail(lineNo, field + ".metadata", "SCHEMA_VIOLATION", problem);
        }

        return new Outcome(new Valid(lineNo, caseKey, taskType, node.get("input"),
                normalizeOptional(node.get("expected")), normalizeOptional(node.get("metadata"))), null);
    }

    /**
     * 单条创建/修改的校验入口：规则与 JSONL 完全一致，失败以 {@link BusinessException}（400）抛出。
     *
     * @param prefix 字段路径前缀，例如 {@code body}；用于组装 {@code body.caseKey} 这类定位信息
     */
    public Valid requireCase(String prefix, String caseKey, String taskType,
                             JsonNode input, JsonNode expected, JsonNode metadata) {
        String problem = caseKeyProblem(caseKey);
        if (problem != null) {
            throw BusinessException.field(prefix + ".caseKey", "INVALID_CASE_KEY " + problem);
        }
        problem = taskTypeProblem(taskType);
        if (problem != null) {
            throw BusinessException.field(prefix + ".taskType", "UNKNOWN_TASK_TYPE " + problem);
        }
        problem = inputProblem(input);
        if (problem != null) {
            throw BusinessException.field(prefix + ".input", "SCHEMA_VIOLATION " + problem);
        }
        problem = metadataProblem(metadata);
        if (problem != null) {
            throw BusinessException.field(prefix + ".metadata", "SCHEMA_VIOLATION " + problem);
        }
        return new Valid(0, caseKey, taskType, input,
                normalizeOptional(expected), normalizeOptional(metadata));
    }

    /** 把任意 JSON 值规范成"要么节点、要么 null"，避免 JSON null 与缺失混用。 */
    public static JsonNode normalizeOptional(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /**
     * caseKey 规则：1~128 字符，不允许控制字符。
     *
     * <p>刻意不限制成某个字符白名单：caseKey 常来自用户已有的样本编号（可能含中文、斜杠、冒号），
     * 收窄字符集会把合法数据拒之门外。长度与控制字符足以覆盖真正的坏输入。
     */
    private String caseKeyProblem(String key) {
        if (key == null || key.isEmpty()) {
            return "caseKey 必须是非空字符串";
        }
        if (key.length() > MAX_CASE_KEY_LENGTH) {
            return "caseKey 长度不能超过 " + MAX_CASE_KEY_LENGTH;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                return "caseKey 不能包含控制字符";
            }
        }
        return null;
    }

    private String taskTypeProblem(String taskType) {
        if (taskType == null || taskType.isEmpty()) {
            return "taskType 必须是非空字符串";
        }
        if (taskType.length() > MAX_TASK_TYPE_LENGTH) {
            return "taskType 长度不能超过 " + MAX_TASK_TYPE_LENGTH;
        }
        try {
            TaskType.require(taskType);
            return null;
        } catch (RuntimeException ex) {
            return "不支持的 taskType: " + taskType;
        }
    }

    private String inputProblem(JsonNode input) {
        return input != null && input.isObject() ? null : "input 必须是 JSON 对象";
    }

    private String metadataProblem(JsonNode metadata) {
        return metadata == null || metadata.isNull() || metadata.isObject()
                ? null : "metadata 必须是对象或 null";
    }

    private static String field(int lineNo) {
        return "lines[" + lineNo + "]";
    }

    /**
     * 行级错误：{@code message} 首词固定是错误码。
     *
     * <p>契约把错误码放在 message 首词而不是独立字段（信封已冻结），前端与脚本都按这个约定解析，
     * 因此前缀必须在这里统一加，不能让调用点各自拼。
     */
    private Outcome fail(int lineNo, String field, String code, String message) {
        return new Outcome(null, new LineError(lineNo, field, code, code + " " + message));
    }
}
