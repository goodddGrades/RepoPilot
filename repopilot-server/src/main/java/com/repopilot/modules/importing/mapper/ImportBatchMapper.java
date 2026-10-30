package com.repopilot.modules.importing.mapper;

import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.ImportType;
import com.repopilot.modules.importing.entity.ImportBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
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

    // ------------------------------------------------------------------ TASK-005

    /**
     * 锁定批次行。
     *
     * <p>每行的业务写入、取消、计数回写都先锁这一行：取消与"提交下一行"因此在同一把锁上排队，
     * 不会出现"取消已返回但下一行仍然提交成功"。
     */
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
                FOR UPDATE
            """)
    Optional<ImportBatch> findByIdForUpdate(@Param("id") String id);

    /** 分页查询；条件为空时不参与过滤。 */
    @Select("""
            <script>
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
             WHERE 1 = 1
             <if test="importType != null">AND import_type = #{importType}</if>
             <if test="status != null">AND status = #{status}</if>
             <if test="experimentId != null">AND experiment_id = #{experimentId}</if>
             <if test="datasetVersionId != null">AND dataset_version_id = #{datasetVersionId}</if>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ImportBatch> search(@Param("importType") ImportType importType,
                             @Param("status") BatchStatus status,
                             @Param("experimentId") String experimentId,
                             @Param("datasetVersionId") String datasetVersionId,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM import_batches
             WHERE 1 = 1
             <if test="importType != null">AND import_type = #{importType}</if>
             <if test="status != null">AND status = #{status}</if>
             <if test="experimentId != null">AND experiment_id = #{experimentId}</if>
             <if test="datasetVersionId != null">AND dataset_version_id = #{datasetVersionId}</if>
            </script>
            """)
    long count(@Param("importType") ImportType importType,
               @Param("status") BatchStatus status,
               @Param("experimentId") String experimentId,
               @Param("datasetVersionId") String datasetVersionId);

    /** PENDING→RUNNING；已被取消的批次不会被"开始处理"覆盖。 */
    @Update("""
            UPDATE import_batches
               SET status = 'RUNNING',
                   started_at = COALESCE(started_at, #{startedAt}),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int markRunning(@Param("id") String id,
                    @Param("startedAt") Instant startedAt,
                    @Param("updatedAt") Instant updatedAt);

    /** 计数与进度回写（由 import_records 重算，非本机累加）。 */
    @Update("""
            UPDATE import_batches
               SET total_count = #{totalCount},
                   success_count = #{successCount},
                   failed_count = #{failedCount},
                   duplicate_count = #{duplicateCount},
                   last_line_no = #{lastLineNo},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateCounts(@Param("id") String id,
                     @Param("totalCount") long totalCount,
                     @Param("successCount") long successCount,
                     @Param("failedCount") long failedCount,
                     @Param("duplicateCount") long duplicateCount,
                     @Param("lastLineNo") long lastLineNo,
                     @Param("updatedAt") Instant updatedAt);

    /** 终态写入：只在期望的旧状态上生效，避免把已取消批次改回 COMPLETED。 */
    @Update("""
            UPDATE import_batches
               SET status = #{status},
                   total_count = #{totalCount},
                   success_count = #{successCount},
                   failed_count = #{failedCount},
                   duplicate_count = #{duplicateCount},
                   last_line_no = #{lastLineNo},
                   finished_at = #{finishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = #{expectedStatus}
            """)
    int finish(@Param("id") String id,
               @Param("expectedStatus") BatchStatus expectedStatus,
               @Param("status") BatchStatus status,
               @Param("totalCount") long totalCount,
               @Param("successCount") long successCount,
               @Param("failedCount") long failedCount,
               @Param("duplicateCount") long duplicateCount,
               @Param("lastLineNo") long lastLineNo,
               @Param("finishedAt") Instant finishedAt,
               @Param("updatedAt") Instant updatedAt);

    /** PENDING/RUNNING→CANCELLED。 */
    @Update("""
            UPDATE import_batches
               SET status = 'CANCELLED',
                   finished_at = #{finishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int cancel(@Param("id") String id,
               @Param("finishedAt") Instant finishedAt,
               @Param("updatedAt") Instant updatedAt);

    /** 崩溃恢复：仍然停在 PENDING/RUNNING 的批次。 */
    @Select("""
            SELECT id
              FROM import_batches
             WHERE status IN ('PENDING', 'RUNNING')
             ORDER BY created_at ASC, id ASC
             LIMIT #{limit}
            """)
    List<String> findUnfinished(@Param("limit") int limit);

    /**
     * 成功一行的增量计数。
     *
     * <p>增量只用于"过程中大致能看到进度"；收尾时由 {@code import_records} 全量重算覆盖，
     * 因此并发或重放导致的漂移不会留在最终结果里。
     * {@code duplicateDelta} 是 success 的子集——契约要求 {@code duplicate_count <= success_count}。
     */
    @Update("""
            UPDATE import_batches
               SET success_count = success_count + 1,
                   duplicate_count = duplicate_count + #{duplicateDelta},
                   last_line_no = GREATEST(last_line_no, #{lineNo}),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int incrementSuccess(@Param("id") String id,
                         @Param("lineNo") Long lineNo,
                         @Param("duplicateDelta") int duplicateDelta,
                         @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE import_batches
               SET failed_count = failed_count + 1,
                   last_line_no = GREATEST(last_line_no, #{lineNo}),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int incrementFailed(@Param("id") String id,
                        @Param("lineNo") Long lineNo,
                        @Param("updatedAt") Instant updatedAt);
}
