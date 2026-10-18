package com.repopilot.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;

/**
 * 把原始 {@link JsonNode} 转成请求 DTO。
 *
 * <p>Controller 统一用 {@code JsonNode} 接请求体，原因有二：
 * <ol>
 *   <li>幂等摘要需要对**原始请求内容**取规范化 hash；先转 DTO 再序列化会丢掉"字段缺失"与
 *       "显式 null"的区别。</li>
 *   <li>字段类型错误时能给出稳定的 400，而不是让 Jackson 的异常直接穿透成 500。</li>
 * </ol>
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    public static <T> T convert(ObjectMapper mapper, JsonNode body, Class<T> type) {
        if (body == null || body.isNull()) {
            throw BusinessException.field("body", "请求体不能为空");
        }
        if (!body.isObject()) {
            throw BusinessException.field("body", "请求体必须是 JSON 对象");
        }
        try {
            return mapper.treeToValue(body, type);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求体字段类型不正确: " + ex.getMessage());
        }
    }
}
