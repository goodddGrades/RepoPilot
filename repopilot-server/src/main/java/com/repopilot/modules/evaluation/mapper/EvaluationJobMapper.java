package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.entity.EvaluationJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_jobs} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationJobMapper {

    @Insert("""
            INSERT INTO evaluation_jobs (id, run_id, batch_id, evaluation_plan_version_id, evaluation_plan_item_id, status, attempt_count, active_attempt_id, next_retry_at, input_hash, started_at, finished_at, error_code, error_message, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluationPlanVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluationPlanItemId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{status},
                   #{attemptCount},
                   #{activeAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{nextRetryAt},
                   #{inputHash},
                   #{startedAt},
                   #{finishedAt},
                   #{errorCode},
                   #{errorMessage},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationJob entity);

    @Select("""
            SELECT id,
                   run_id,
                   batch_id,
                   evaluation_plan_version_id,
                   evaluation_plan_item_id,
                   status,
                   attempt_count,
                   active_attempt_id,
                   next_retry_at,
                   input_hash,
                   started_at,
                   finished_at,
                   error_code,
                   error_message,
                   created_at,
                   updated_at
              FROM evaluation_jobs
              WHERE id = #{id}
            """)
    Optional<EvaluationJob> findById(@Param("id") String id);

    // ---- TASK-004：完成封口后幂等生成 PENDING Job（规则引擎在 TASK-007） ----

    /**
     * 批量插入 Job。
     *
     * <p>唯一键 {@code (batch_id, run_id, plan_item_id)} 是幂等的最终保证：
     * 即使重复完成回调绕过服务层判断，数据库也会拒绝第二条。
     */
    @Insert("""
            <script>
            INSERT INTO evaluation_jobs (id, run_id, batch_id, evaluation_plan_version_id, evaluation_plan_item_id, status, attempt_count, active_attempt_id, next_retry_at, input_hash, started_at, finished_at, error_code, error_message, created_at, updated_at)
            VALUES
            <foreach collection="jobs" item="j" separator=",">
            (#{j.id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.evaluationPlanVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.evaluationPlanItemId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.status},
             #{j.attemptCount},
             #{j.activeAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{j.nextRetryAt},
             #{j.inputHash},
             #{j.startedAt},
             #{j.finishedAt},
             #{j.errorCode},
             #{j.errorMessage},
             #{j.createdAt},
             #{j.updatedAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("jobs") List<EvaluationJob> jobs);

    @Select("""
            <script>
            SELECT j.id,
                   j.run_id,
                   j.batch_id,
                   b.batch_no,
                   j.evaluation_plan_version_id,
                   j.evaluation_plan_item_id,
                   i.execution_order,
                   i.`required`,
                   d.evaluator_key,
                   d.version AS evaluator_version,
                   j.status,
                   j.attempt_count,
                   j.error_code
              FROM evaluation_jobs j
              JOIN evaluation_batches b ON b.id = j.batch_id
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
              JOIN evaluator_definitions d ON d.id = i.evaluator_definition_id
             WHERE j.run_id = #{runId}
            <if test="batchId != null"> AND j.batch_id = #{batchId} </if>
             ORDER BY b.batch_no ASC, i.execution_order ASC, j.id ASC
            </script>
            """)
    List<JobListRow> listByRun(@Param("runId") String runId,
                               @Param("batchId") String batchId);

    /** 某 Run 在某批次下已存在的方案项 id；用于"只补缺失"的幂等创建。 */
    @Select("""
            SELECT evaluation_plan_item_id
              FROM evaluation_jobs
             WHERE batch_id = #{batchId}
               AND run_id = #{runId}
            """)
    List<String> listPlanItemIds(@Param("batchId") String batchId, @Param("runId") String runId);

    /** 取消实验时：本实验所有批次里尚未执行的 Job 置 SKIPPED（已运行 Job 由 TASK-007 处理）。 */
    @Update("""
            UPDATE evaluation_jobs j
              JOIN evaluation_batches b ON b.id = j.batch_id
               SET j.status = 'SKIPPED',
                   j.finished_at = #{now},
                   j.updated_at = #{now}
             WHERE b.experiment_id = #{experimentId}
               AND j.status = 'PENDING'
            """)
    int skipPendingByExperiment(@Param("experimentId") String experimentId,
                                @Param("now") Instant now);
}
