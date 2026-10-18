package com.repopilot.common.state;

/**
 * {@code run_attempts.status / evaluation_job_attempts.status} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "run_attempts", column = "status")
@DictionaryCheck(table = "evaluation_job_attempts", column = "status")
public enum AttemptStatus {

    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED;
}
