package com.repopilot.common.state;

/**
 * {@code annotation_records.action} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：CLAIM, SUBMIT, REVIEW, RETURN, CANCEL。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "annotation_records", column = "action")
public enum AnnotationAction {

    CLAIM,
    SUBMIT,
    REVIEW,
    RETURN,
    CANCEL;
}
