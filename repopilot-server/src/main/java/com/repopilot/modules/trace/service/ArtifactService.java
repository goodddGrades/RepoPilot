package com.repopilot.modules.trace.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.state.ArtifactStatus;
import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.StorageException;
import com.repopilot.modules.trace.dto.ArtifactDtos;
import com.repopilot.modules.trace.entity.Artifact;
import com.repopilot.modules.trace.mapper.ArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Artifact 上传、读取与下载。
 *
 * <p>上传顺序是**先落对象存储、再写元数据**：反过来的话，元数据提交成功而对象写入失败，
 * 会留下一条指向空文件的 READY 记录，下载时才暴露问题。
 * 对象写入成功但事务失败时，catch 分支删除刚写入的对象，避免孤儿文件。
 *
 * <p>对象键由 {@link ArtifactStorage} 生成，调用方只给种类前缀；用户文件名从不进入路径。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** 与契约的"文件默认 20 MiB"一致；导入文件与后续轨迹附件共用这一上限。 */
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    /** 允许的种类。IMPORT_FILE 是 TASK-005 引入的导入原文件。 */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("OUTPUT", "STDOUT", "EVIDENCE", "IMPORT_FILE");

    private static final String DEFAULT_TYPE = "IMPORT_FILE";

    private final ArtifactMapper artifactMapper;
    private final ArtifactStorage storage;
    private final TransactionTemplate tx;

    public ArtifactService(ArtifactMapper artifactMapper, ArtifactStorage storage,
                           PlatformTransactionManager transactionManager) {
        this.artifactMapper = artifactMapper;
        this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传文件并登记元数据。
     *
     * @param runId 可空：导入原文件在创建批次前不属于任何 Run
     */
    public ArtifactDtos.ArtifactView upload(MultipartFile file, String artifactType, String runId) {
        byte[] content = readBytes(file);
        String type = normalizeType(artifactType);
        String normalizedRunId = blankToNull(runId);
        if (normalizedRunId != null && !Ulid.isValid(normalizedRunId)) {
            throw BusinessException.field("runId", "runId 必须是 26 位 ULID");
        }

        AtomicReference<String> objectKey = new AtomicReference<>();
        try {
            return tx.execute(status -> {
                ArtifactStorage.StoredObject stored;
                try {
                    stored = storage.put(prefixOf(type), content, contentTypeOf(file));
                } catch (StorageException ex) {
                    log.error("Artifact 写入失败: type={}, size={}", type, content.length, ex);
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "文件保存失败，请稍后重试");
                }
                objectKey.set(stored.objectKey());

                Artifact artifact = new Artifact();
                artifact.setId(Ulid.newId());
                artifact.setRunId(normalizedRunId);
                artifact.setArtifactType(type);
                artifact.setStorageProvider(stored.provider());
                artifact.setObjectKey(stored.objectKey());
                artifact.setContentType(contentTypeOf(file));
                artifact.setSizeBytes(stored.sizeBytes());
                artifact.setSha256(stored.sha256());
                artifact.setStatus(ArtifactStatus.READY);
                artifact.setCreatedAt(Instant.now());
                artifactMapper.insert(artifact);
                return ArtifactDtos.ArtifactView.from(artifact);
            });
        } catch (RuntimeException ex) {
            deleteQuietly(objectKey.get());
            throw ex;
        }
    }

    public ArtifactDtos.ArtifactView get(String id) {
        return ArtifactDtos.ArtifactView.from(require(id));
    }

    /** 下载：V1 直接回字节流（本地存储无需签名 URL）。 */
    public ArtifactDtos.Download download(String id) {
        Artifact artifact = require(id);
        if (artifact.getStatus() != ArtifactStatus.READY) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Artifact 不可用: " + id);
        }
        try {
            byte[] content = storage.get(artifact.getObjectKey());
            return new ArtifactDtos.Download(artifact.getContentType(), content, artifact.getSha256());
        } catch (StorageException ex) {
            log.error("Artifact 读取失败: id={}", id, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "原文件不可读: " + id);
        }
    }

    public Artifact require(String id) {
        return artifactMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Artifact 不存在: " + id));
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.field("file", "file 不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "文件超过 " + (MAX_BYTES / 1024 / 1024) + " MiB 上限");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取上传文件");
        }
    }

    private static String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_TYPE;
        }
        String type = value.trim();
        if (!ALLOWED_TYPES.contains(type)) {
            throw BusinessException.field("artifactType",
                    "不支持的 artifactType: " + type + "，可选值: " + String.join(", ", ALLOWED_TYPES));
        }
        return type;
    }

    private static String contentTypeOf(MultipartFile file) {
        String type = file.getContentType();
        return type == null || type.isBlank() ? "application/octet-stream" : type;
    }

    private static String prefixOf(String artifactType) {
        return "IMPORT_FILE".equals(artifactType) ? "imports" : "artifacts";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            // 清理失败只记录：此时正在抛原始异常，覆盖它会让真正的失败原因消失
            log.error("清理孤儿 Artifact 失败，需人工处理: {}", key, ex);
        }
    }
}
