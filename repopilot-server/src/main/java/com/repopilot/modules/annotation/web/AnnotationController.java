package com.repopilot.modules.annotation.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.annotation.dto.AnnotationDtos;
import com.repopilot.modules.annotation.service.AnnotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 人工标注接口（TASK-009）。
 *
 * <p>所有动作都要求 {@code Idempotency-Key}：标注是人工操作，网络重试或用户
 * 双击都不该产生两条历史记录。正文里还必须带 {@code expectedVersion}，
 * 让并发操作在数据库层就分出胜负，而不是靠"最后写赢"。
 *
 * <p>接口不接受调用方自称的 {@code operatorId}：V1 没有真实鉴权，
 * 操作者由后端开发配置固定，前端只需要在页面上标明"开发模式"。
 */
@RestController
@RequestMapping("/api/v1")
public class AnnotationController {

    private final AnnotationService annotationService;

    public AnnotationController(AnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    @PostMapping("/annotation-tasks")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        IdempotentResult<AnnotationDtos.TaskView> result = annotationService
                .create(body, IdempotencyDigest.requireKey(idempotencyKey));
        return Responses.of(result);
    }

    @GetMapping("/annotation-tasks")
    public ResponseEntity<ApiResponse<PageResponse<AnnotationDtos.TaskView>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(annotationService.list(status, assigneeId, runId, page, pageSize));
    }

    @GetMapping("/annotation-tasks/{id}")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> detail(@PathVariable String id) {
        return Responses.ok(annotationService.detail(id));
    }

    /** 建议人工复核的 Run：技术失败或自动评测判未通过，且当前没有未终态标注任务。 */
    @GetMapping("/experiments/{id}/annotation-candidates")
    public ResponseEntity<ApiResponse<List<AnnotationDtos.DispatchHint>>> candidates(
            @PathVariable String id,
            @RequestParam(required = false) Integer limit) {
        return Responses.ok(annotationService.dispatchCandidates(id, limit == null ? 50 : limit));
    }

    @PostMapping("/annotation-tasks/{id}/claim")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> claim(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(annotationService.claim(id, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/annotation-tasks/{id}/submit")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> submit(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(annotationService.submit(id, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/annotation-tasks/{id}/review")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> review(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(annotationService.review(id, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/annotation-tasks/{id}/cancel")
    public ResponseEntity<ApiResponse<AnnotationDtos.TaskView>> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(annotationService.cancel(id, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }
}