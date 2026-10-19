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
     *
     * <p>{@code input} 是自由结构而不是固定 record：RUN 与 EVALUATION 的输入形状不同
     * （前者是 {@link RunInput}，后者是评测快照）。用一个字段承载、按 {@code kind} 解释，
     * 比在一个 record 里堆十几个只对一种任务有意义的字段更不容易误用。
     * {@code jobId} 只在 {@code kind=EVALUATION} 时出现。
     */
    public record WorkItem(String kind,
                           String runId,
                           String attemptId,
                           int attemptNo,
                           String leaseToken,
                           Instant leaseUntil,
                           Instant deadlineAt,
                           JsonNode input,
                           String jobId) {
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

    // ================================================================== TASK-007

    /**
     * 评测输入快照（{@code kind=EVALUATION} 的 {@code input}）。
     *
     * <p>{@code acceptedRunSnapshot} 只描述**已封口**的执行结果：被拒绝或失败的 Attempt
     * 不会出现在这里，失败重试的 Trace 也不会被合并进一次成功调用。
     * 离线 Run 没有 Attempt，{@code acceptedAttemptId} 为 null，
     * {@code trace.eventsUrl} 不带 attemptId 过滤。
     */
    public record EvaluationInput(String schemaVersion,
                                  String jobId,
                                  String batchId,
                                  int executionOrder,
                                  boolean required,
                                  EvaluatorPayload evaluator,
                                  JsonNode config,
                                  RunSnapshot acceptedRunSnapshot) {
    }

    public record EvaluatorPayload(String key, int version, String type, String implementationRef) {
    }

    public record RunSnapshot(String runId,
                              String mode,
                              String taskOutcome,
                              String acceptedAttemptId,
                              JsonNode output,
                              String outputArtifactId,
                              JsonNode usage,
                              CasePayload datasetCase,
                              TraceSnapshot trace) {
    }

    /**
     * 封口轨迹的读取凭据。
     *
     * <p>{@code eventsUrl} 是后端相对路径，Worker 只能读这一个 Run 的已封口事件；
     * {@code manifestSha256} 与 {@code eventCount} 用于校验"读到的就是封口时的清单"，
     * 数量或摘要不符时按技术错误处理，不拿残缺轨迹去判分。
     */
    public record TraceSnapshot(boolean sealed, String eventsUrl, int eventCount, String manifestSha256) {
    }

    public record JobCompleteRequest(String leaseToken,
                                     String verdict,
                                     java.math.BigDecimal score,
                                     String reason,
                                     JsonNode evidence,
                                     JsonNode result,
                                     String generatedBy,
                                     JsonNode usage) {
    }

    public record JobCompleteResult(String attemptId, String status, String jobId) {
    }

    public record JobFailRequest(String leaseToken,
                                 String errorCode,
                                 String errorMessage,
                                 Boolean retryableHint,
                                 JsonNode usage) {
    }

    public record JobFailResult(String attemptId,
                                String status,
                                String jobId,
                                boolean retryScheduled,
                                Instant nextRetryAt) {
    }
}
