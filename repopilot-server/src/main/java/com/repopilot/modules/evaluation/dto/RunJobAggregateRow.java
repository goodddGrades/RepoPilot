package com.repopilot.modules.evaluation.dto;

/**
 * 某 Run 在某个批次下的 Job 汇总，用于判定 Run 的技术终态。
 *
 * <p>{@code requiredFailed} 只统计 required 的 FAILED/SKIPPED：optional Job 失败不降技术成功率。
 * 质量判定（verdict=FAIL）不参与这里——它属于 {@code evaluation_results}，不是 Job 状态。
 */
public class RunJobAggregateRow {

    public Long totalJobs;
    public Long nonTerminalJobs;
    public Long requiredJobs;
    public Long requiredCompleted;
    public Long requiredFailed;
}
