package com.repopilot.modules.dataset.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.dataset.entity.Dataset;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.entity.DatasetVersion;

import java.time.Instant;

/**
 * 数据集模块的请求/响应形状。
 *
 * <p>{@code caseCount} 是现算的（不落库）：样本数会随草稿编辑变化，
 * 存成列就必须在每个写路径维护它，必然有一天对不上。
 */
public final class DatasetDtos {

    private DatasetDtos() {
    }

    public record CreateRequest(String code, String name, String description) {
    }

    public record DatasetView(String id, String code, String name, String description,
                              LifecycleStatus status, Instant createdAt, Instant updatedAt) {

        public static DatasetView from(Dataset entity) {
            return new DatasetView(entity.getId(), entity.getCode(), entity.getName(),
                    entity.getDescription(), entity.getStatus(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    public record CreateVersionRequest(String description, String cloneFromVersionId) {
    }

    public record VersionView(String id, String datasetId, Integer version, VersionStatus status,
                              String description, String contentHash, Instant publishedAt,
                              long caseCount, Instant createdAt, Instant updatedAt) {

        public static VersionView from(DatasetVersion entity, long caseCount) {
            return new VersionView(entity.getId(), entity.getDatasetId(), entity.getVersion(),
                    entity.getStatus(), entity.getDescription(), entity.getContentHash(),
                    entity.getPublishedAt(), caseCount, entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 单条创建/修改样本。不含 {@code schemaVersion}：那是 JSONL 文件格式的字段，不是库字段。 */
    public record CaseUpsertRequest(String caseKey, String taskType, JsonNode input,
                                     JsonNode expected, JsonNode metadata) {
    }

    public record CaseView(String id, String datasetVersionId, String caseKey, String taskType,
                           JsonNode input, JsonNode expected, JsonNode metadata,
                           Instant createdAt, Instant updatedAt) {

        public static CaseView from(DatasetCase entity) {
            return new CaseView(entity.getId(), entity.getDatasetVersionId(), entity.getCaseKey(),
                    entity.getTaskType(), entity.getInputJson(), entity.getExpectedJson(),
                    entity.getMetadataJson(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 导入回执。整批成功才有这个结构；任何一行失败都不会返回它。 */
    public record ImportReceipt(String importBatchId, String versionId, String status,
                                int importedCount, int totalCount, long sizeBytes, String sha256) {
    }

    public record DeleteResult(String id, boolean deleted) {
    }
}
