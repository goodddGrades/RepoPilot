package com.repopilot.modules.experiment.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.experiment.service.RunQueryService;
import com.repopilot.modules.trace.dto.RunDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Run 读取接口。只读：执行状态只能由 Worker 回调与后台扫描推进。 */
@RestController
@RequestMapping("/api/v1")
public class RunController {

    private final RunQueryService runQueryService;

    public RunController(RunQueryService runQueryService) {
        this.runQueryService = runQueryService;
    }

    @GetMapping("/experiments/{id}/runs")
    public ResponseEntity<ApiResponse<PageResponse<RunDtos.RunView>>> list(
            @PathVariable String id,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String caseKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskOutcome,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(runQueryService.list(id, targetId, caseKey, status, taskOutcome,
                page, pageSize));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<RunDtos.RunDetail>> get(@PathVariable String id) {
        return Responses.ok(runQueryService.detail(id));
    }

    @GetMapping("/runs/{id}/attempts")
    public ResponseEntity<ApiResponse<PageResponse<RunDtos.AttemptView>>> attempts(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(runQueryService.attempts(id, page, pageSize));
    }

    @GetMapping("/runs/{id}/evaluation-jobs")
    public ResponseEntity<ApiResponse<RunDtos.JobSummaryView>> jobs(
            @PathVariable String id,
            @RequestParam(required = false) String batchId) {
        return Responses.ok(runQueryService.jobs(id, batchId));
    }
}
