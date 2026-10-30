package com.repopilot.modules.experiment.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.experiment.dto.ExperimentDtos;
import com.repopilot.modules.experiment.service.ExperimentService;
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
 * 实验接口。
 *
 * <p>启动成功后立即做一次 PENDING→QUEUED 推进，让本地演示不必等后台扫描；
 * 推进失败不回滚已提交的实验，扫描器会重做。
 */
@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/experiments")
    public ResponseEntity<ApiResponse<ExperimentDtos.ExperimentView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(experimentService.create(body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/experiments")
    public ResponseEntity<ApiResponse<PageResponse<ExperimentDtos.ExperimentView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String keyword) {
        return Responses.ok(experimentService.list(page, pageSize, status, mode, keyword));
    }

    @GetMapping("/experiments/{id}")
    public ResponseEntity<ApiResponse<ExperimentDtos.ExperimentView>> get(@PathVariable String id) {
        return Responses.ok(experimentService.get(id));
    }

    @PostMapping("/experiments/{id}/start")
    public ResponseEntity<ApiResponse<ExperimentDtos.StatusResult>> start(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        IdempotentResult<ExperimentDtos.StatusResult> result =
                experimentService.start(id, IdempotencyDigest.requireKey(idempotencyKey));
        if (!result.replayed()) {
            // 事务已提交；这里失败只影响"立刻可领取"，不影响正确性
            experimentService.promoteAfterStart(id);
        }
        return Responses.of(result);
    }

    @PostMapping("/experiments/{id}/cancel")
    public ResponseEntity<ApiResponse<ExperimentDtos.StatusResult>> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        return Responses.of(experimentService.cancel(id, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }
}
