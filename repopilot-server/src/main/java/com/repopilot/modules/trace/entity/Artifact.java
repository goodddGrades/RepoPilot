package com.repopilot.modules.trace.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * 对象存储元数据，可先上传后关联Run。
 *
 * <p>对应表 {@code artifacts}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class Artifact {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 导入原文件可为空 */
    private String runId;
    /** IMPORT_FILE/OUTPUT/STDOUT/EVIDENCE等注册类型 */
    private String artifactType;
    /** 对象存储注册名 */
    private String storageProvider;
    /** 内部对象路径，不含签名URL */
    private String objectKey;
    /** MIME类型 */
    private String contentType;
    /** 服务端校验长度 */
    private Long sizeBytes;
    /** 服务端校验SHA256 */
    private String sha256;
    /** 只在字节检查通过后创建READY记录 */
    private ArtifactStatus status;
    /** UTC创建时间 */
    private Instant createdAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getArtifactType() {
        return artifactType;
    }

    public void setArtifactType(String artifactType) {
        this.artifactType = artifactType;
    }

    public String getStorageProvider() {
        return storageProvider;
    }

    public void setStorageProvider(String storageProvider) {
        this.storageProvider = storageProvider;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public ArtifactStatus getStatus() {
        return status;
    }

    public void setStatus(ArtifactStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
