package com.repopilot.modules.trace.dto;

import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;

import java.time.Instant;

/**
 * Run 列表的一行（Run 列 + 关联展示字段）。
 *
 * <p>用可写类而不是 record：MyBatis 的结果映射对 record 的构造器参数名依赖编译参数，
 * 换构建方式就可能静默映射失败。这里字段赋值是显式的，失败会立刻表现为 null 而不是错位。
 */
public class RunListRow {

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
    public Instant sealedAt;
    public Instant startedAt;
    public Instant finishedAt;
    public Instant createdAt;
}
