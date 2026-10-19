package com.repopilot.modules.report.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.report.dto.ReportDtos;
import com.repopilot.modules.report.service.ReportAggregationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实验报告接口（TASK-010）。
 *
 * <p>纯只读：没有 POST/PUT/DELETE，也不要求 {@code Idempotency-Key}——
 * 报告不改变任何状态，重复调用天然幂等，加幂等键反而是无意义的仪式。
 *
 * <p>参数全部可选且都有明确的默认值语义（{@code batchScope=INITIAL}、
 * {@code annotationView=AUTOMATIC}），这些默认值写在契约里，前端不得自行推断。
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ReportAggregationService reports;

    public ReportController(ReportAggregationService reports) {
        this.reports = reports;
    }

    /**
     * 实验报告。
     *
     * @param batchId            显式批次；不传则按 {@code batchScope} 解析
     * @param batchScope         {@code INITIAL}（默认，取初始批次）或 {@code LATEST}
     * @param baselineTargetKey  基线 Target；不传取第一个
     * @param candidateTargetKey 候选 Target；不传则不产出比较与门禁
     * @param annotationView     {@code AUTOMATIC}（默认）或 {@code REVIEWED}
     */
    @GetMapping("/experiments/{experimentId}/report")
    public ResponseEntity<ApiResponse<ReportDtos.ExperimentReport>> report(
            @PathVariable String experimentId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String batchScope,
            @RequestParam(required = false) String baselineTargetKey,
            @RequestParam(required = false) String candidateTargetKey,
            @RequestParam(required = false) String annotationView) {
        return Responses.ok(reports.report(experimentId, batchId, batchScope,
                baselineTargetKey, candidateTargetKey, annotationView));
    }
}