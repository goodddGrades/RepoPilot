package com.repopilot.modules.evaluationconfig.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanService;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 评测方案与其版本列表。 */
@RestController
@RequestMapping("/api/v1")
public class EvaluationPlanController {

    private final EvaluationPlanService planService;
    private final EvaluationPlanVersionService versionService;

    public EvaluationPlanController(EvaluationPlanService planService,
                                    EvaluationPlanVersionService versionService) {
        this.planService = planService;
        this.versionService = versionService;
    }

    @PostMapping("/evaluation-plans")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(planService.create(body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/evaluation-plans")
    public ResponseEntity<ApiResponse<PageResponse<EvaluationDtos.PlanView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Responses.ok(planService.list(page, pageSize, keyword));
    }

    @GetMapping("/evaluation-plans/{id}")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanView>> get(@PathVariable String id) {
        return Responses.ok(planService.get(id));
    }

    @PostMapping("/evaluation-plans/{id}/versions")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanVersionView>> createVersion(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(versionService.create(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/evaluation-plans/{id}/versions")
    public ResponseEntity<ApiResponse<PageResponse<EvaluationDtos.PlanVersionView>>> listVersions(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(versionService.list(id, page, pageSize));
    }
}
