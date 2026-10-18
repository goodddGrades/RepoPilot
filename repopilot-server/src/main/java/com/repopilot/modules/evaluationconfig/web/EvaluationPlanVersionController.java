package com.repopilot.modules.evaluationconfig.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单个方案版本：读取、整体替换草稿、发布、归档。 */
@RestController
@RequestMapping("/api/v1/evaluation-plan-versions")
public class EvaluationPlanVersionController {

    private final EvaluationPlanVersionService service;

    public EvaluationPlanVersionController(EvaluationPlanVersionService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanVersionView>> get(@PathVariable String id) {
        return Responses.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanVersionView>> replace(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(service.replace(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanVersionView>> publish(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(service.publish(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<EvaluationDtos.PlanVersionView>> archive(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(service.archive(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }
}
