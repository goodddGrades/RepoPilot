package com.repopilot.modules.importing.dto;

import com.repopilot.modules.importing.entity.ImportBatch;
import com.repopilot.modules.importing.entity.ImportError;

import java.time.Instant;

/**
 * 导入接口的请求与响应结构。
 *
 * <p>{@code totalCount} 只在扫描结束后才有意义：过程中它可能是 0（还不知道有多少行），
 * 因此另给一个 {@code totalKnown} 标志，前端据此展示"已处理 N 行"而不是一个假的百分比。
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    public record CreateRequest(String importType,
                                String datasetVersionId,
                                String experimentId,
                                String fileArtifactId) {
    }

    public record BatchView(String id,
                            String importType,
                            String status,
                            String datasetVersionId,
                            String experimentId,
                            String fileArtifactId,
                            long totalCount,
                            boolean totalKnown,
                            long processedCount,
                            long successCount,
                            long failedCount,
                            long duplicateCount,
                            long lastLineNo,
                            Instant startedAt,
                            Instant finishedAt,
                            Instant createdAt,
                            Instant updatedAt) {

        public static BatchView from(ImportBatch batch) {
            long success = batch.getSuccessCount() == null ? 0 : batch.getSuccessCount();
            long failed = batch.getFailedCount() == null ? 0 : batch.getFailedCount();
            String status = batch.getStatus() == null ? null : batch.getStatus().name();
            boolean terminal = "COMPLETED".equals(status) || "PARTIAL_FAILED".equals(status)
                    || "FAILED".equals(status) || "CANCELLED".equals(status);
            return new BatchView(
                    batch.getId(),
                    batch.getImportType() == null ? null : batch.getImportType().name(),
                    status,
                    batch.getDatasetVersionId(),
                    batch.getExperimentId(),
                    batch.getFileArtifactId(),
                    batch.getTotalCount() == null ? 0 : batch.getTotalCount(),
                    terminal,
                    success + failed,
                    success,
                    failed,
                    batch.getDuplicateCount() == null ? 0 : batch.getDuplicateCount(),
                    batch.getLastLineNo() == null ? 0 : batch.getLastLineNo(),
                    batch.getStartedAt(),
                    batch.getFinishedAt(),
                    batch.getCreatedAt(),
                    batch.getUpdatedAt());
        }
    }

    /** 错误摘要：只有行号、错误码与说明，不含原始行内容。 */
    public record ErrorView(long lineNo, String errorCode, String errorMessage) {

        public static ErrorView from(ImportError error) {
            return new ErrorView(error.getLineNo() == null ? 0 : error.getLineNo(),
                    error.getErrorCode(), error.getErrorMessage());
        }
    }
}
