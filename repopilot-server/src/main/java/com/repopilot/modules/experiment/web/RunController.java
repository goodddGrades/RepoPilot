package com.repopilot.modules.experiment.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.experiment.service.RunQueryService;
import com.repopilot.modules.trace.dto.RunDtos;
import com.repopilot.modules.trace.dto.TraceDtos;
import com.repopilot.modules.trace.service.TraceQueryService;
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
    private final TraceQueryService traceQueryService;

    public RunController(RunQueryService runQueryService, TraceQueryService traceQueryService) {
        this.runQueryService = runQueryService;
        this.traceQueryService = traceQueryService;
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

    /**
     * Trace 事件分页（补全契约 TASK-006 一栏的 {@code GET /runs/{id}/events}）。
     *
     * <p>与其它列表接口不同，这里不用 page/pageSize：轨迹是追加且可能迟到的，
     * 用页码分页会在新事件插入旧时间段时重复或漏行。游标携带
     * {@code (occurredAt, id)} 与过滤条件摘要，换条件必须重置。
     */
    @GetMapping("/runs/{id}/events")
    public ResponseEntity<ApiResponse<TraceDtos.EventPage>> events(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String attemptId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Boolean allAttempts) {
        return Responses.ok(traceQueryService.events(id, cursor, limit, attemptId, eventType,
                allAttempts));
    }
}
