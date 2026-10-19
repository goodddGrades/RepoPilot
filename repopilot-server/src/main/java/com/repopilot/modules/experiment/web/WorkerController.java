package com.repopilot.modules.experiment.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.experiment.dto.WorkerDtos;
import com.repopilot.modules.experiment.service.WorkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Worker 协议入口（{@code /internal/v1}）。
 *
 * <p>鉴权在 {@link WorkerAuthFilter} 中完成；Controller 只做参数解包。
 * 所有写接口都要求 {@code leaseToken}，它既是身份也是 fencing 令牌。
 */
@RestController
@RequestMapping("/internal/v1")
public class WorkerController {

    private final WorkService workService;
    private final com.repopilot.modules.evaluation.service.JobAttemptService jobAttemptService;

    public WorkerController(WorkService workService,
                            com.repopilot.modules.evaluation.service.JobAttemptService jobAttemptService) {
        this.workService = workService;
        this.jobAttemptService = jobAttemptService;
    }

    @PostMapping("/work/claim")
    public ResponseEntity<ApiResponse<WorkerDtos.ClaimResult>> claim(
            @RequestBody WorkerDtos.ClaimRequest request) {
        return Responses.ok(workService.claim(request));
    }

    @PostMapping("/run-attempts/{id}/heartbeat")
    public ResponseEntity<ApiResponse<WorkerDtos.HeartbeatResult>> heartbeat(
            @PathVariable String id, @RequestBody WorkerDtos.HeartbeatRequest request) {
        return Responses.ok(workService.heartbeat(id, request));
    }

    @PostMapping("/run-attempts/{id}/events")
    public ResponseEntity<ApiResponse<WorkerDtos.EventsResult>> events(
            @PathVariable String id, @RequestBody WorkerDtos.EventsRequest request) {
        return Responses.ok(workService.appendEvents(id, request));
    }

    @PostMapping("/run-attempts/{id}/complete")
    public ResponseEntity<ApiResponse<WorkerDtos.CompleteResult>> complete(
            @PathVariable String id, @RequestBody WorkerDtos.CompleteRequest request) {
        return Responses.ok(workService.complete(id, request));
    }

    @PostMapping("/run-attempts/{id}/fail")
    public ResponseEntity<ApiResponse<WorkerDtos.FailResult>> fail(
            @PathVariable String id, @RequestBody WorkerDtos.FailRequest request) {
        return Responses.ok(workService.fail(id, request));
    }

    @PostMapping("/artifacts")
    public ResponseEntity<ApiResponse<WorkerDtos.ArtifactView>> upload(
            @RequestParam String runId,
            @RequestParam String attemptId,
            @RequestParam String leaseToken,
            @RequestParam(required = false) String artifactType,
            @RequestPart("file") MultipartFile file) throws IOException {
        return Responses.created(workService.uploadArtifact(runId, attemptId, leaseToken,
                artifactType, file.getContentType(), file.getBytes()));
    }

    // ------------------------------------------------------------------ 评测（TASK-007）

    @PostMapping("/job-attempts/{id}/heartbeat")
    public ResponseEntity<ApiResponse<WorkerDtos.HeartbeatResult>> jobHeartbeat(
            @PathVariable String id, @RequestBody WorkerDtos.HeartbeatRequest request) {
        return Responses.ok(jobAttemptService.heartbeat(id, request.leaseToken()));
    }

    /**
     * 提交评测结论。
     *
     * <p>{@code verdict=FAIL} 是质量失败，Job 仍为 COMPLETED；只有运行异常才走 {@code /fail}。
     * 同一正文重放返回原回执，不同正文 409。
     */
    @PostMapping("/job-attempts/{id}/complete")
    public ResponseEntity<ApiResponse<WorkerDtos.JobCompleteResult>> jobComplete(
            @PathVariable String id, @RequestBody WorkerDtos.JobCompleteRequest request) {
        return Responses.ok(jobAttemptService.complete(id, request));
    }

    @PostMapping("/job-attempts/{id}/fail")
    public ResponseEntity<ApiResponse<WorkerDtos.JobFailResult>> jobFail(
            @PathVariable String id, @RequestBody WorkerDtos.JobFailRequest request) {
        return Responses.ok(jobAttemptService.fail(id, request));
    }
}
