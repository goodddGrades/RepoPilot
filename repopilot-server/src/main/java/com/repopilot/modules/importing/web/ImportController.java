package com.repopilot.modules.importing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.importing.dto.ImportDtos;
import com.repopilot.modules.importing.service.ImportService;
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
 * 导入批次接口。
 *
 * <p>创建返回 202：批次只完成了"受理"（文件已校验、批次已落库），逐行处理在后台串行进行。
 * 调用方通过 {@code GET /imports/{id}} 读取进度，而不是等一个可能跑几十秒的请求。
 */
@RestController
@RequestMapping("/api/v1")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/imports")
    public ResponseEntity<ApiResponse<ImportDtos.BatchView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        IdempotentResult<ImportDtos.BatchView> result = importService.create(body,
                IdempotencyDigest.requireKey(idempotencyKey));
        return Responses.of(result);
    }

    @GetMapping("/imports")
    public ResponseEntity<ApiResponse<PageResponse<ImportDtos.BatchView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String importType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String experimentId,
            @RequestParam(required = false) String datasetVersionId) {
        return Responses.ok(importService.list(page, pageSize, importType, status,
                experimentId, datasetVersionId));
    }

    @GetMapping("/imports/{id}")
    public ResponseEntity<ApiResponse<ImportDtos.BatchView>> get(@PathVariable String id) {
        return Responses.ok(importService.get(id));
    }

    @GetMapping("/imports/{id}/errors")
    public ResponseEntity<ApiResponse<PageResponse<ImportDtos.ErrorView>>> errors(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(importService.errors(id, page, pageSize));
    }

    @PostMapping("/imports/{id}/cancel")
    public ResponseEntity<ApiResponse<ImportDtos.BatchView>> cancel(@PathVariable String id) {
        return ResponseEntity.status(202).body(ApiResponse.ok(importService.cancel(id)));
    }
}
