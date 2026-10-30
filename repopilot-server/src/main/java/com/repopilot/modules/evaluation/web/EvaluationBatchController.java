package com.repopilot.modules.evaluation.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.evaluation.dto.EvaluationBatchDtos;
import com.repopilot.modules.evaluation.service.EvaluationBatchService;
import com.repopilot.modules.trace.dto.RunDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测批次接口（TASK-007）。
 *
 * <p>批次是重评的唯一入口：创建新批次、复用已封口 Run、不重跑 Agent。
 * 读取接口只写只读视图，取消只影响本批次。
 */
@RestController
@RequestMapping("/api/v1")
public class EvaluationBatchController {

    private final EvaluationBatchService batchService;

    public EvaluationBatchController(EvaluationBatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping("/experiments/{id}/evaluation-batches")
    public ResponseEntity<ApiResponse<EvaluationBatchDtos.BatchView>> create(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        IdempotentResult<EvaluationBatchDtos.BatchView> result = batchService.createBatch(id, body,
                IdempotencyDigest.requireKey(idempotencyKey));
        return Responses.of(result);
    }

    @GetMapping("/experiments/{id}/evaluation-batches")
    public ResponseEntity<ApiResponse<PageResponse<EvaluationBatchDtos.BatchView>>> list(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(batchService.list(id, status, page, pageSize));
    }

    @GetMapping("/evaluation-batches/{id}")
    public ResponseEntity<ApiResponse<EvaluationBatchDtos.BatchView>> detail(@PathVariable String id) {
        return Responses.ok(batchService.detail(id));
    }

    @GetMapping("/evaluation-batches/{id}/runs")
    public ResponseEntity<ApiResponse<PageResponse<RunDtos.RunView>>> runs(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(batchService.runs(id, page, pageSize));
    }

    @PostMapping("/evaluation-batches/{id}/cancel")
    public ResponseEntity<ApiResponse<EvaluationBatchDtos.BatchView>> cancel(
            @PathVariable String id,
            @RequestBody(required = false) JsonNode body) {
        String reason = body == null ? null : body.path("reason").asText(null);
        return Responses.of(new IdempotentResult<>(batchService.cancel(id, reason), 202, false));
    }
}
