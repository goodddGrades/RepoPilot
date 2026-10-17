package com.repopilot.common.api;

/**
 * 字段级校验错误项。
 *
 * @param field   字段路径，例如 {@code name} 或 {@code cases[0].caseKey}
 * @param message 校验失败原因
 */
public record FieldViolation(String field, String message) {
}
