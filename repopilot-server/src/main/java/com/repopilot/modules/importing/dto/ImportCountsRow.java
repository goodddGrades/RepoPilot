package com.repopilot.modules.importing.dto;

/**
 * 按回执状态聚合出的计数。
 *
 * <p>批次进度**只认这张表**：进程内累加器在崩溃、重放、并发读取下都不可信，
 * 而 {@code import_records} 每行唯一（batch + line），从这里重算的结果必然正确。
 *
 * <p>用可写类而不是 record：MyBatis 结果映射依赖构造器参数名时容易随编译参数变化静默失效。
 */
public class ImportCountsRow {

    public long imported;
    public long reused;
    public long rejected;

    public long success() {
        return imported + reused;
    }

    public long duplicate() {
        return reused;
    }

    public long failed() {
        return rejected;
    }
}
