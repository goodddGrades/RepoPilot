package com.repopilot.modules.dataset.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.service.DatasetService;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据集与版本接口。
 *
 * <p>Controller 只做三件事：取幂等键、调服务、包信封。状态校验全部在服务层的事务与行锁内完成。
 */
@RestController
@RequestMapping("/api/v1")
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetVersionService versionService;

    public DatasetController(DatasetService datasetService, DatasetVersionService versionService) {
        this.datasetService = datasetService;
        this.versionService = versionService;
    }

    @PostMapping("/datasets")
    public ResponseEntity<ApiResponse<DatasetDtos.DatasetView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(datasetService.create(body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/datasets")
    public ResponseEntity<ApiResponse<PageResponse<DatasetDtos.DatasetView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Responses.ok(datasetService.list(page, pageSize, status, keyword));
    }

    @GetMapping("/datasets/{id}")
    public ResponseEntity<ApiResponse<DatasetDtos.DatasetView>> get(@PathVariable String id) {
        return Responses.ok(datasetService.get(id));
    }

    @PatchMapping("/datasets/{id}")
    public ResponseEntity<ApiResponse<DatasetDtos.DatasetView>> patch(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(datasetService.patch(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/datasets/{id}/versions")
    public ResponseEntity<ApiResponse<DatasetDtos.VersionView>> createVersion(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(versionService.create(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/datasets/{id}/versions")
    public ResponseEntity<ApiResponse<PageResponse<DatasetDtos.VersionView>>> listVersions(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(versionService.list(id, page, pageSize));
    }
}
