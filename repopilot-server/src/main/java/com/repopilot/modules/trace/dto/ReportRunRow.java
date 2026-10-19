package com.repopilot.modules.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;

import java.time.Instant;

/**
 * 报告用的 Run 行：配对键 + 技术状态 + 最终观察的usage。
 *
 * <p>与 {@link RunListRow} 的分工：{@code RunListRow} 服务于页面列表（不读 usage_json，
 * 因为列表不需要成本）；本行专为聚合而存在，必须带上
 * {@code datasetCaseId}（配对键的一半）与 {@code usageJson}（最终观察成本口径）。
 *
 * <p>字段为 public 的原因与 {@code RunListRow} 相同：MyBatis 结果映射对 record 的
 * 构造器参数名依赖编译参数，换构建方式就可能静默映射失败。
 */
public class ReportRunRow {

    public String id;
    public String experimentId;
    public String experimentTargetId;
    public String targetKey;
    public String datasetCaseId;
    public String caseKey;
    public Integer repeatIndex;
    public RunMode mode;
    public RunStatus status;
    public TaskOutcome taskOutcome;
    public Integer attemptCount;
    public String acceptedAttemptId;
    /** 最终观察的 usage（在线取Run 上的、离线取导入的）；无成本信息时为 {@code null}，不是空对象。 */
    public JsonNode usageJson;
    public Instant startedAt;
    public Instant finishedAt;
}