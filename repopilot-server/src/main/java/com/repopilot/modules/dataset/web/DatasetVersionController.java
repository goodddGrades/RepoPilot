package com.repopilot.modules.dataset.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.service.DatasetCaseService;
import com.repopilot.modules.dataset.service.DatasetImportService;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单个数据集版本：读取、发布、归档、样本读取与 JSONL 批量导入。
 *
 * <p>{@code cases:import} 用字面冒号而不是子路径，是为了让"动作"与"子资源"在 URL 上可区分：
 * {@code /cases} 是集合，{@code /cases:import} 是动作。
 */
@RestController
@RequestMapping("/api/v1/dataset-versions")
public class DatasetVersionController {

    private final DatasetVersionService versionService;
    private final DatasetCaseService caseService;
    private final DatasetImportService importService;

    public DatasetVersionController(DatasetVersionService versionService,
                                    DatasetCaseService caseService,
                                    DatasetImportService importService) {
        this.versionService = versionService;
        this.caseService = caseService;
        this.importService = importService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DatasetDtos.VersionView>> get(@PathVariable String id) {
        return Responses.ok(versionService.get(id));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<DatasetDtos.VersionView>> publish(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(versionService.publish(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<DatasetDtos.VersionView>> archive(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(versionService.archive(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/{id}/cases")
    public ResponseEntity<ApiResponse<PageResponse<DatasetDtos.CaseView>>> listCases(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Responses.ok(caseService.list(id, page, pageSize, keyword));
    }

    /**
     * JSONL 整批导入。
     *
     * <p>用 {@code @RequestPart("file")} 而不是 {@code @RequestParam}：前者按 part 名取，
     * 与契约里"字段 file=<*.jsonl>"一致，也不依赖表单里的 filename。
     */
    @PostMapping(path = "/{id}/cases:import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DatasetDtos.ImportReceipt>> importCases(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart("file") MultipartFile file) {
        return Responses.of(importService.importCases(id, file,
                IdempotencyDigest.requireKey(idempotencyKey)));
    }
}
