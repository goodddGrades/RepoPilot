package com.repopilot.modules.trace.service;

import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.StorageException;
import com.repopilot.modules.trace.mapper.ArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Artifact 存储的孤儿清理。
 *
 * <p>什么时候会有孤儿：上传是"先写对象、再写元数据"，元数据事务失败时 {@link ArtifactService}
 * 会尝试删除对象；如果进程在删除前被杀，文件就留在存储里，而数据库里没有它。
 * 这类文件永远不会被下载接口读到，只会慢慢占满磁盘。
 *
 * <p>两条安全边界：
 * <ul>
 *   <li><b>只删没有元数据的对象</b>：{@code artifacts.object_key} 里存在的键一律跳过，
 *       不管它有多老——元数据在就说明它仍是可访问的证据。</li>
 *   <li><b>只删超过宽限期的对象</b>：刚写入但事务尚未提交的对象在数据库里还看不到，
 *       宽限期把它排除在外。</li>
 * </ul>
 *
 * <p>不删除"有元数据但对象丢了"的反向情况：那是数据损坏，应该告警而不是静默重建元数据。
 */
@Service
public class ArtifactMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactMaintenanceService.class);

    private final ArtifactMapper artifactMapper;
    private final ArtifactStorage storage;

    public ArtifactMaintenanceService(ArtifactMapper artifactMapper, ArtifactStorage storage) {
        this.artifactMapper = artifactMapper;
        this.storage = storage;
    }

    /**
     * 删除 {@code olderThan} 之前写入、且数据库里没有对应元数据的对象。
     *
     * @return 实际删除的数量
     */
    public int deleteOrphanObjects(Instant olderThan) {
        Set<String> known = new HashSet<>(artifactMapper.listObjectKeys());
        int deleted = 0;
        for (ArtifactStorage.StoredEntry entry : storage.list()) {
            if (known.contains(entry.objectKey()) || !entry.lastModified().isBefore(olderThan)) {
                continue;
            }
            try {
                storage.delete(entry.objectKey());
                deleted++;
                log.info("删除孤儿对象: {}（{} 字节，写入于 {}）",
                        entry.objectKey(), entry.sizeBytes(), entry.lastModified());
            } catch (StorageException ex) {
                // 单个对象删不掉不影响其它对象；下一轮会重试
                log.warn("删除孤儿对象失败，下一轮重试: {}", entry.objectKey(), ex);
            }
        }
        return deleted;
    }
}
