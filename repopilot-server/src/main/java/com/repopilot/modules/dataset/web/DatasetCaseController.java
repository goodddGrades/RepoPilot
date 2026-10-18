package com.repopilot.modules.dataset.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.service.DatasetCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单条样本：创建、整体替换、删除。写操作只允许父版本为草稿。 */
@RestController
@RequestMapping("/api/v1")
public class DatasetCaseController {

    private final DatasetCaseService caseService;

    public DatasetCaseController(DatasetCaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping("/dataset-versions/{versionId}/cases")
    public ResponseEntity<ApiResponse<DatasetDtos.CaseView>> create(
            @PathVariable String versionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(caseService.create(versionId, body,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PutMapping("/dataset-cases/{id}")
    public ResponseEntity<ApiResponse<DatasetDtos.CaseView>> update(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(caseService.update(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @DeleteMapping("/dataset-cases/{id}")
    public ResponseEntity<ApiResponse<DatasetDtos.DeleteResult>> delete(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(caseService.delete(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }
}
