package com.repopilot.modules.importing.mapper;

import com.repopilot.modules.importing.entity.ImportRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code import_records} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ImportRecordMapper {

    @Insert("""
            INSERT INTO import_records (id, batch_id, line_no, record_hash, status, dataset_case_id, run_id, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{lineNo},
                   #{recordHash},
                   #{status},
                   #{datasetCaseId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{createdAt})
            """)
    int insert(ImportRecord entity);

    @Select("""
            SELECT id,
                   batch_id,
                   line_no,
                   record_hash,
                   status,
                   dataset_case_id,
                   run_id,
                   created_at
              FROM import_records
              WHERE id = #{id}
            """)
    Optional<ImportRecord> findById(@Param("id") String id);

    // ------------------------------------------------------------------ TASK-005

    /** 每行处理前的回执检查：同批次同物理行只允许一条回执。 */
    @Select("""
            SELECT id,
                   batch_id,
                   line_no,
                   record_hash,
                   status,
                   dataset_case_id,
                   run_id,
                   created_at
              FROM import_records
             WHERE batch_id = #{batchId}
               AND line_no = #{lineNo}
            """)
    Optional<ImportRecord> findByBatchAndLine(@Param("batchId") String batchId,
                                              @Param("lineNo") long lineNo);

    /** 计数重算：一次读出三种状态的行数。 */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status = 'IMPORTED' THEN 1 ELSE 0 END), 0) AS imported,
                   COALESCE(SUM(CASE WHEN status = 'REUSED' THEN 1 ELSE 0 END), 0) AS reused,
                   COALESCE(SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END), 0) AS rejected
              FROM import_records
             WHERE batch_id = #{batchId}
            """)
    com.repopilot.modules.importing.dto.ImportCountsRow countsByStatus(
            @Param("batchId") String batchId);

    /** 已处理的最大物理行号；恢复时从它之后继续。 */
    @Select("""
            SELECT COALESCE(MAX(line_no), 0)
              FROM import_records
             WHERE batch_id = #{batchId}
            """)
    long maxLineNo(@Param("batchId") String batchId);

    /** 本批次成功建立的 Run 标识（演示与测试用来核对关联）。 */
    @Select("""
            SELECT run_id
              FROM import_records
             WHERE batch_id = #{batchId}
               AND run_id IS NOT NULL
             ORDER BY line_no ASC
            """)
    List<String> listRunIds(@Param("batchId") String batchId);
}
