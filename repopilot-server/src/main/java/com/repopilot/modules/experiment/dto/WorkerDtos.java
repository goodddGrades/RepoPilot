package com.repopilot.modules.experiment.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Worker 协议 DTO（{@code /internal/v1}）。
 *
 * <p>所有请求都用 record 承载"已经过字段级校验"的值；原始 {@link JsonNode} 只在
 * 事件与配置这类自由结构上保留。响应里绝不回传 {@code leaseTokenHash}。
 */
public final class WorkerDtos {

    private WorkerDtos() {
    }

    public record ClaimRequest(String workerId, List<String> capabilities, Integer maxTasks) {
    }

    public record ClaimResult(List<WorkItem> tasks) {
    }

    /**
     * 一个可执行任务。
     *
     * <p>{@code leaseToken} 明文只在这里出现一次；它不写入日志、不写数据库。
     */
    public record WorkItem(String kind,
                           String runId,
                           String attemptId,
                           int attemptNo,
                           String leaseToken,
                           Instant leaseUntil,
                           Instant deadlineAt,
                           RunInput input) {
    }

    public record RunInput(String experimentId,
                           String targetKey,
                           String agentVersionId,
                           String runnerType,
                           JsonNode agentConfig,
                           JsonNode budget,
                           CasePayload datasetCase) {
    }

    public record CasePayload(String caseId,
                              String caseKey,
                              String taskType,
                              JsonNode input,
                              JsonNode expected,
                              JsonNode metadata) {
    }

    public record HeartbeatRequest(String leaseToken) {
    }

    public record HeartbeatResult(Instant leaseUntil, Instant deadlineAt, boolean cancelled) {
    }

    public record EventItem(String eventId,
                            String eventType,
                            Instant occurredAt,
                            Long sequenceNo,
                            String traceId,
                            String spanId,
                            String parentSpanId,
                            String sourceEventId,
                            String artifactId,
                            JsonNode payload) {
    }

    public record EventsRequest(String leaseToken, List<EventItem> events) {
    }

    public record EventAckItem(String eventId, String status) {
    }

    public record EventsResult(List<EventAckItem> items) {
    }

    public record CompleteRequest(String leaseToken,
                                  String taskOutcome,
                                  JsonNode output,
                                  String outputArtifactId,
                                  JsonNode usage,
                                  JsonNode observationMetadata,
                                  Integer expectedEventCount,
                                  String eventManifestSha256) {
    }

    public record CompleteResult(String attemptId, String status) {
    }

    public record FailRequest(String leaseToken,
                              String errorCode,
                              String errorMessage,
                              Boolean retryableHint,
                              JsonNode usage) {
    }

    public record FailResult(String attemptId,
                             String status,
                             String runId,
                             boolean retryScheduled,
                             Instant nextRetryAt) {
    }

    public record ArtifactView(String id,
                               String runId,
                               String artifactType,
                               String contentType,
                               long sizeBytes,
                               String sha256,
                               String status,
                               Instant createdAt) {
    }
}
