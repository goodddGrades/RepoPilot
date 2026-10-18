package com.repopilot.common.idempotency;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.json.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 幂等请求摘要。
 *
 * <p>{@code sha256(method + path + 规范化(body) + 额外参数)}。用规范化后的 body 而不是原始字节：
 * 前端字段顺序变化（JSON 对象本身无序）不应该被当成"另一个请求"。
 *
 * <p>额外参数用于导入这类 body 之外还有关键输入的接口（文件 {@code sha256}），
 * 保证"同 key 但换了文件"能被识别成冲突而不是静默复用旧回执。
 */
public final class IdempotencyDigest {

    private static final String SEPARATOR = "\n";

    private IdempotencyDigest() {
    }

    public static String of(String method, String path, JsonNode body, String... extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.toUpperCase()).append(SEPARATOR).append(path);
        sb.append(SEPARATOR).append(body == null ? "" : CanonicalJson.canonicalize(body));
        if (extra != null) {
            for (String item : extra) {
                sb.append(SEPARATOR).append(item == null ? "" : item);
            }
        }
        return Sha256.ofUtf8(sb.toString());
    }

    /** 缺少必填头时的统一错误。契约要求所有写接口都带 {@code Idempotency-Key}。 */
    public static String requireKey(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "缺少 Idempotency-Key 请求头",
                    java.util.List.of(new com.repopilot.common.api.FieldViolation("Idempotency-Key",
                            "写操作必须携带 Idempotency-Key")));
        }
        String trimmed = headerValue.trim();
        if (trimmed.length() > 128) {
            throw BusinessException.field("Idempotency-Key", "Idempotency-Key 长度不能超过 128");
        }
        return trimmed;
    }
}
