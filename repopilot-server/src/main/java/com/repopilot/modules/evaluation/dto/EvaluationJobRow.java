package com.repopilot.modules.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.EvaluatorType;

/**
 * 一次可领取的评测 Job（Job + 方案项 + 算子定义的投影）。
 *
 * <p>字段为 public 的原因与 {@code RunListRow} 相同：避免 record 构造器映射依赖编译参数。
 * 只有领取路径使用它；对外视图仍走 {@code RunDtos.JobView}。
 */
public class EvaluationJobRow {

    public String jobId;
    public String runId;
    public String batchId;
    public String planVersionId;
    public String planItemId;
    public String evaluatorKey;
    public Integer evaluatorVersion;
    public EvaluatorType evaluatorType;
    public String implementationRef;
    public Integer executionOrder;
    public Boolean required;
    public JsonNode configJson;
    public Integer attemptCount;
    public String inputHash;
}
