package com.repopilot.modules.evaluation.mapper;

import com.repopilot.common.state.BatchStatus;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_batches} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationBatchMapper {

    @Insert("""
            INSERT INTO evaluation_batches (id, experiment_id, evaluation_plan_version_id, batch_no, trigger_type, parent_batch_id, selection_json, status, expected_job_count, created_by, started_at, finished_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluationPlanVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{batchNo},
                   #{triggerType},
                   #{parentBatchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{selectionJson},
                   #{status},
                   #{expectedJobCount},
                   #{createdBy},
                   #{startedAt},
                   #{finishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationBatch entity);

    @Select("""
            SELECT id,
                   experiment_id,
                   evaluation_plan_version_id,
                   batch_no,
                   trigger_type,
                   parent_batch_id,
                   initial_experiment_id,
                   selection_json,
                   status,
                   expected_job_count,
                   created_by,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_batches
              WHERE id = #{id}
            """)
    Optional<EvaluationBatch> findById(@Param("id") String id);

    // ---- TASK-004：实验启动时创建 INITIAL 批次 ----

    /** 初始批次唯一性由生成列 + 唯一键保证，这里只做读取。 */
    @Select("""
            SELECT id,
                   experiment_id,
                   evaluation_plan_version_id,
                   batch_no,
                   trigger_type,
                   parent_batch_id,
                   initial_experiment_id,
                   selection_json,
                   status,
                   expected_job_count,
                   created_by,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_batches
             WHERE initial_experiment_id = #{experimentId}
            """)
    Optional<EvaluationBatch> findInitialByExperiment(@Param("experimentId") String experimentId);

    @Select("SELECT COALESCE(MAX(batch_no), 0) + 1 FROM evaluation_batches WHERE experiment_id = #{experimentId}")
    int nextBatchNo(@Param("experimentId") String experimentId);

    @Update("""
            UPDATE evaluation_batches
               SET status = #{status},
                   started_at = #{startedAt},
                   finished_at = #{finishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = #{expected}
            """)
    int updateStatus(@Param("id") String id,
                     @Param("expected") BatchStatus expected,
                     @Param("status") BatchStatus status,
                     @Param("startedAt") Instant startedAt,
                     @Param("finishedAt") Instant finishedAt,
                     @Param("updatedAt") Instant updatedAt);

    /** 首批 Job 落库时把批次从 PENDING 推到 RUNNING；重复完成回调不会把它改回去。 */
    @Update("""
            UPDATE evaluation_batches
               SET status = 'RUNNING',
                   started_at = COALESCE(started_at, #{now}),
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int markRunning(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE evaluation_batches
               SET status = 'CANCELLED',
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE experiment_id = #{experimentId}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int cancelNonTerminalByExperiment(@Param("experimentId") String experimentId,
                                      @Param("now") Instant now);
}
