package com.repopilot.modules.trace.dto;

import com.repopilot.modules.trace.entity.Artifact;

import java.time.Instant;

/**
 * Artifact 对外视图。
 *
 * <p><b>刻意不含 {@code objectKey}</b>：那是存储层内部路径（本地实现下就是文件系统路径）。
 * 契约要求"不把本地文件系统路径直接暴露给 API 调用方"，所以对外只有 id 与下载端点。
 */
public final class ArtifactDtos {

    private ArtifactDtos() {
    }

    public record ArtifactView(String id,
                               String runId,
                               String artifactType,
                               String contentType,
                               long sizeBytes,
                               String sha256,
                               String status,
                               Instant createdAt) {

        public static ArtifactView from(Artifact artifact) {
            return new ArtifactView(
                    artifact.getId(),
                    artifact.getRunId(),
                    artifact.getArtifactType(),
                    artifact.getContentType(),
                    artifact.getSizeBytes() == null ? 0L : artifact.getSizeBytes(),
                    artifact.getSha256(),
                    artifact.getStatus() == null ? null : artifact.getStatus().name(),
                    artifact.getCreatedAt());
        }
    }

    /** 文件下载内容（内部使用，不进入 JSON 信封）。 */
    public record Download(String contentType, byte[] content, String sha256) {
    }
}
