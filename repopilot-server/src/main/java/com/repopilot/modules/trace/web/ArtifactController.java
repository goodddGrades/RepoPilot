package com.repopilot.modules.trace.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.modules.trace.dto.ArtifactDtos;
import com.repopilot.modules.trace.service.ArtifactService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 业务侧 Artifact 接口（Worker 侧上传见 {@code /internal/v1/artifacts}）。
 *
 * <p>下载返回原始字节而非 JSON 信封：它是二进制资源，包一层 base64 只会让前端多一次解码。
 * 出错时仍走统一错误 JSON（由异常处理器接管），因此契约里"异常返回公共错误 JSON"成立。
 */
@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @PostMapping(path = "/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ArtifactDtos.ArtifactView>> upload(
            @RequestParam(required = false) String artifactType,
            @RequestParam(required = false) String runId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(artifactService.upload(file, artifactType, runId)));
    }

    @GetMapping("/artifacts/{id}")
    public ResponseEntity<ApiResponse<ArtifactDtos.ArtifactView>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(artifactService.get(id)));
    }

    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        ArtifactDtos.Download download = artifactService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, download.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + id + ".jsonl\"")
                .header("X-Content-Sha256", download.sha256())
                .body(download.content());
    }
}
