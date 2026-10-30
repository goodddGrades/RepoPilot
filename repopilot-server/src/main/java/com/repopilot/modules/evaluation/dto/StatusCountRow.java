package com.repopilot.modules.evaluation.dto;

/** 批次内按状态分组的 Job 计数（{@code SELECT status, COUNT(*)}）。 */
public class StatusCountRow {

    public String status;
    public Long total;
}
