package com.repopilot.modules.trace.mapper;

import com.repopilot.modules.trace.entity.Artifact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code artifacts} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ArtifactMapper {

    @Insert("""
            INSERT INTO artifacts (id, run_id, artifact_type, storage_provider, object_key, content_type, size_bytes, sha256, status, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{artifactType},
                   #{storageProvider},
                   #{objectKey},
                   #{contentType},
                   #{sizeBytes},
                   #{sha256},
                   #{status},
                   #{createdAt})
            """)
    int insert(Artifact entity);

    @Select("""
            SELECT id,
                   run_id,
                   artifact_type,
                   storage_provider,
                   object_key,
                   content_type,
                   size_bytes,
                   sha256,
                   status,
                   created_at
              FROM artifacts
              WHERE id = #{id}
            """)
    Optional<Artifact> findById(@Param("id") String id);
}
