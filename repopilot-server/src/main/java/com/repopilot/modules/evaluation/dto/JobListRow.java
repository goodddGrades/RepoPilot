package com.repopilot.modules.evaluation.dto;

import com.repopilot.common.state.JobStatus;

/**
 * Run 的评测 Job 行（Job + 方案项 + 算子标识）。
 *
 * <p>字段为 public 的原因与 {@code RunListRow} 相同：避免 record 构造器映射依赖编译参数。
 */
public class JobListRow {

    public String id;
    public String runId;
    public String batchId;
    public Integer batchNo;
    public String evaluationPlanVersionId;
    public String evaluationPlanItemId;
    public Integer executionOrder;
    public Boolean required;
    public String evaluatorKey;
    public Integer evaluatorVersion;
    public String evaluatorType;
    public JobStatus status;
    public Integer attemptCount;
    public String errorCode;
    public String errorMessage;
    public java.time.Instant startedAt;
    public java.time.Instant finishedAt;
}
