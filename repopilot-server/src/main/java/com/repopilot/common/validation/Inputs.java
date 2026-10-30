package com.repopilot.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.exception.BusinessException;

import java.util.regex.Pattern;

/**
 * 入参校验的最小工具集。
 *
 * <p>刻意不用 Bean Validation 注解：契约要求错误响应里带**精确的字段路径**与中文可读原因
 * （例如 {@code items[2].evaluatorDefinitionId}），注解方式要么写一堆自定义校验器，
 * 要么只能给出笼统消息。这里集中实现，消息统一，字段路径由调用方给。
 */
public final class Inputs {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private Inputs() {
    }

    public static String required(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field(field, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw BusinessException.field(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    public static String optional(String field, String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw BusinessException.field(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    /** 业务标识：1~64 位 {@code [A-Za-z0-9._-]}，大小写敏感。 */
    public static String code(String field, String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field(field, field + " 不能为空");
        }
        if (!CODE.matcher(value).matches()) {
            throw BusinessException.field(field,
                    field + " 只能包含字母、数字、点、下划线、连字符，长度 1~64");
        }
        return value;
    }

    /** 必须是 JSON 对象（不能是数组、标量或 null）。 */
    public static JsonNode object(String field, JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw BusinessException.field(field, field + " 必须是 JSON 对象");
        }
        return node;
    }

    /**
     * LIKE 关键字转义。
     *
     * <p>不转义时用户输入 {@code %} 会把查询变成"全表匹配"，行为看起来像"筛选没生效"。
     * 配合 SQL 里的 {@code ESCAPE '\\'} 使用。
     */
    public static String likeKeyword(String keyword, int maxLength) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > maxLength) {
            throw BusinessException.field("keyword", "keyword 长度不能超过 " + maxLength);
        }
        return trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
