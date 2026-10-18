package com.repopilot.modules.importing.mapper;

import com.repopilot.modules.importing.entity.ImportBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code import_batches} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ImportBatchMapper {

    @Insert("""
            INSERT INTO import_batches (id, import_type, dataset_version_id, experiment_id, file_artifact_id, status, total_count, success_count, failed_count, duplicate_count, last_line_no, started_at, finished_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{importType},
                   #{datasetVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{fileArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{status},
                   #{totalCount},
                   #{successCount},
                   #{failedCount},
                   #{duplicateCount},
                   #{lastLineNo},
                   #{startedAt},
                   #{finishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(ImportBatch entity);

    @Select("""
            SELECT id,
                   import_type,
                   dataset_version_id,
                   experiment_id,
                   file_artifact_id,
                   status,
                   total_count,
                   success_count,
                   failed_count,
                   duplicate_count,
                   last_line_no,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM import_batches
              WHERE id = #{id}
            """)
    Optional<ImportBatch> findById(@Param("id") String id);
}
