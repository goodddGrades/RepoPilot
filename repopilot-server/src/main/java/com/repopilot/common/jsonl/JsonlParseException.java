package com.repopilot.common.jsonl;

import com.repopilot.common.api.ErrorCode;

/**
 * 文件级解析失败。
 *
 * <p>与"行级错误"区分：这里意味着整份文件不可接受（超限、编码错、单行过长），
 * 调用方必须直接返回 413/400，且**不得创建批次、不得写样本、不得保存原文件**。
 */
public class JsonlParseException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String field;

    public JsonlParseException(ErrorCode errorCode, String field, String message) {
        super(message);
        this.errorCode = errorCode;
        this.field = field;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getField() {
        return field;
    }
}
