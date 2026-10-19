package com.repopilot.modules.annotation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * 人工标注接口的对外结构（TASK-009）。
 *
 * <p>刻意**不暴露** {@code operatorId} 的自由输入：V1 没有真正的用户鉴权，
 * 操作者身份由后端开发配置固定（见 {@code app.repopilot.annotation.dev-operator-id}），
 * 否则任何人可以随便填一个 operatorId 冒充他人。
 */
public final class AnnotationDtos {

    private AnnotationDtos() {
    }

    public record TaskView(String id,
                           String runId,
                           String evaluationJobId,
                           String status,
                           String assigneeId,
                           long version,
                           Instant claimedAt,
                           Instant submittedAt,
                           Instant reviewedAt,
                           Instant createdAt,
                           Instant updatedAt,
                           List<RecordView> records) {
    }

    /**
     * 一条历史记录。
     *
     * @param originalVerdict 该记录对应时刻被复核的**自动评测结论**，用于展示"人工改了什么"；
     *                        原始结论永不被覆盖，只是在记录里留一份对照
     */
    public record RecordView(String id,
                             String operatorId,
                             String action,
                             String verdict,
                             String failureCategory,
                             String reason,
                             JsonNode evidence,
                             String originalVerdict,
                             Instant createdAt) {
    }

    public record CreateTaskRequest(String runId, String evaluationJobId) {
    }

    public record SubmitRequest(Long expectedVersion,
                                String verdict,
                                String failureCategory,
                                String reason,
                                JsonNode evidence) {
    }

    public record ReviewRequest(Long expectedVersion,
                                Boolean approved,
                                String reason,
                                String failureCategory,
                                JsonNode evidence) {
    }

    public record CancelRequest(Long expectedVersion, String reason) {
    }

    /** 分派建议：哪些 Run 需要人工复核（自动失败或结论冲突）。 */
    public record DispatchHint(String runId,
                               String evaluationJobId,
                               String reason,
                               String autoVerdict,
                               Instant createdAt) {
    }
}