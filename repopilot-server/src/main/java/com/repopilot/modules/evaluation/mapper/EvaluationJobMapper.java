package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.dto.EvaluationJobRow;
import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.dto.RunJobAggregateRow;
import com.repopilot.modules.evaluation.dto.StatusCountRow;
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
                   d.evaluator_type,
                   j.status,
                   j.attempt_count,
                   j.error_code,
                   j.error_message,
                   j.started_at,
                   j.finished_at
              FROM evaluation_jobs j
              JOIN evaluation_batches b ON b.id = j.batch_id
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
              JOIN evaluator_definitions d ON d.id = i.evaluator_definition_id
             WHERE j.run_id = #{runId}
            <if test="batchId != null"> AND j.batch_id = #{batchId} </if>
             ORDER BY b.batch_no DESC, i.execution_order ASC, j.id ASC
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

    // ================================================================== TASK-007

    /**
     * 可领取的评测 Job 候选。
     *
     * <p>四个条件缺一不可：
     * <ol>
     *   <li>批次与实验都还没结束——取消的实验不应再产生新尝试。这里**允许
     *       {@code COMPLETED} 的实验**：重评历史 Run 时实验早已收口，新批次仍然要被领走，
     *       否则"用新方案重评"会永久停在 PENDING；{@code DRAFT} 不可能有批次，
     *       {@code CANCELLED} 必须排除；</li>
     *   <li>{@code (status='PENDING')} 或"重试到点的 RUNNING"（active Attempt 已清空）；</li>
     *   <li>算子命中 Worker 声明的 {@code EVAL:*} 能力；</li>
     *   <li><b>串行</b>：同一 Run 在本批次里没有更早的未终态 Job。
     *       技术失败（FAILED）也算终态，后续取证算子仍会执行——不自动跳过。</li>
     * </ol>
     * 候选查询只缩小范围，真正的抢占在 {@link #lockClaimCandidate} 里加锁并重新校验。
     */
    @Select("""
            <script>
            SELECT j.id
              FROM evaluation_jobs j
              JOIN evaluation_batches b ON b.id = j.batch_id
              JOIN experiments e ON e.id = b.experiment_id
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
              JOIN evaluator_definitions d ON d.id = i.evaluator_definition_id
             WHERE b.status IN ('PENDING', 'RUNNING')
               AND e.status IN ('QUEUED', 'RUNNING', 'COMPLETED')
               AND ((j.status = 'PENDING')
                    OR (j.status = 'RUNNING' AND j.active_attempt_id IS NULL
                        AND j.next_retry_at IS NOT NULL AND j.next_retry_at &lt;= #{now}))
               AND j.attempt_count &lt; #{maxAttempts}
               AND d.evaluator_key IN
               <foreach collection="evaluatorKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
               AND NOT EXISTS (
                     SELECT 1
                       FROM evaluation_jobs prev
                       JOIN evaluation_plan_items pi ON pi.id = prev.evaluation_plan_item_id
                      WHERE prev.batch_id = j.batch_id
                        AND prev.run_id = j.run_id
                        AND pi.execution_order &lt; i.execution_order
                        AND prev.status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED'))
             ORDER BY j.created_at ASC, j.id ASC
             LIMIT #{limit}
            </script>
            """)
    List<String> listClaimCandidates(@Param("evaluatorKeys") List<String> evaluatorKeys,
                                     @Param("now") Instant now,
                                     @Param("maxAttempts") int maxAttempts,
                                     @Param("limit") int limit);

    /**
     * 锁定一个候选 Job 并**重新校验**全部领取条件。
     *
     * <p>{@code FOR UPDATE OF j SKIP LOCKED} 保证同一 Job 只会被一个 Worker 抢到；
     * 被抢先时返回空，调用方试下一个候选。条件里重新带上实验/批次状态，
     * 因此"候选查询之后实验被取消"不会继续发出任务。
     */
    @Select("""
            <script>
            SELECT j.id AS job_id,
                   j.run_id,
                   j.batch_id,
                   j.evaluation_plan_version_id AS plan_version_id,
                   j.evaluation_plan_item_id AS plan_item_id,
                   d.evaluator_key,
                   d.version AS evaluator_version,
                   d.evaluator_type,
                   d.implementation_ref,
                   i.execution_order,
                   i.`required`,
                   i.config_json,
                   j.attempt_count,
                   j.input_hash
              FROM evaluation_jobs j
              JOIN evaluation_batches b ON b.id = j.batch_id
              JOIN experiments e ON e.id = b.experiment_id
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
              JOIN evaluator_definitions d ON d.id = i.evaluator_definition_id
             WHERE j.id = #{jobId}
               AND b.status IN ('PENDING', 'RUNNING')
               AND e.status IN ('QUEUED', 'RUNNING', 'COMPLETED')
               AND ((j.status = 'PENDING')
                    OR (j.status = 'RUNNING' AND j.active_attempt_id IS NULL
                        AND j.next_retry_at IS NOT NULL AND j.next_retry_at &lt;= #{now}))
               AND j.attempt_count &lt; #{maxAttempts}
               AND d.evaluator_key IN
               <foreach collection="evaluatorKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
               AND NOT EXISTS (
                     SELECT 1
                       FROM evaluation_jobs prev
                       JOIN evaluation_plan_items pi ON pi.id = prev.evaluation_plan_item_id
                      WHERE prev.batch_id = j.batch_id
                        AND prev.run_id = j.run_id
                        AND pi.execution_order &lt; i.execution_order
                        AND prev.status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED'))
               FOR UPDATE OF j SKIP LOCKED
            </script>
            """)
    Optional<EvaluationJobRow> lockClaimCandidate(@Param("jobId") String jobId,
                                                  @Param("evaluatorKeys") List<String> evaluatorKeys,
                                                  @Param("now") Instant now,
                                                  @Param("maxAttempts") int maxAttempts);

    /** PENDING/RUNNING(空闲) → RUNNING 并挂上 active Attempt；fencing 条件在 WHERE 里。 */
    @Update("""
            UPDATE evaluation_jobs
               SET status = 'RUNNING',
                   active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   attempt_count = #{attemptCount},
                   next_retry_at = NULL,
                   started_at = COALESCE(started_at, #{now}),
                   updated_at = #{now}
             WHERE id = #{jobId}
               AND active_attempt_id IS NULL
               AND status IN ('PENDING', 'RUNNING')
            """)
    int markRunning(@Param("jobId") String jobId,
                    @Param("attemptId") String attemptId,
                    @Param("attemptCount") int attemptCount,
                    @Param("now") Instant now);

    /** Attempt 完成 → Job COMPLETED；条件要求 active Attempt 仍是本次尝试。 */
    @Update("""
            UPDATE evaluation_jobs
               SET status = 'COMPLETED',
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{jobId}
               AND status = 'RUNNING'
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int markCompleted(@Param("jobId") String jobId,
                      @Param("attemptId") String attemptId,
                      @Param("now") Instant now);

    /** 不可重试的技术失败 → Job FAILED（保留错误码，供前端区分技术失败与质量 FAIL）。 */
    @Update("""
            UPDATE evaluation_jobs
               SET status = 'FAILED',
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{jobId}
               AND status = 'RUNNING'
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int markFailed(@Param("jobId") String jobId,
                   @Param("attemptId") String attemptId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") Instant now);

    /** 可重试失败：Job 保持 RUNNING，清空 active Attempt 并写下次可领取时间。 */
    @Update("""
            UPDATE evaluation_jobs
               SET active_attempt_id = NULL,
                   next_retry_at = #{nextRetryAt},
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   updated_at = #{now}
             WHERE id = #{jobId}
               AND status = 'RUNNING'
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int releaseForRetry(@Param("jobId") String jobId,
                        @Param("attemptId") String attemptId,
                        @Param("nextRetryAt") Instant nextRetryAt,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("now") Instant now);

    /** 批次取消：待执行 Job 置 SKIPPED。 */
    @Update("""
            UPDATE evaluation_jobs
               SET status = 'SKIPPED',
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE batch_id = #{batchId}
               AND status = 'PENDING'
            """)
    int skipPendingByBatch(@Param("batchId") String batchId, @Param("now") Instant now);

    /**
     * 批次取消：运行中 Job 置 FAILED(error_code=CANCELLED)。
     *
     * <p>为什么不是 SKIPPED：它已经产生过 Attempt 与可能的成本，归到"从未执行"会让
     * 批次计数与 Attempt 历史自相矛盾。错误码固定为 CANCELLED，前端据此显示为"已取消"。
     */
    @Update("""
            UPDATE evaluation_jobs
               SET status = 'FAILED',
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   error_code = 'CANCELLED',
                   error_message = '批次已取消',
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE batch_id = #{batchId}
               AND status = 'RUNNING'
            """)
    int failRunningByBatch(@Param("batchId") String batchId, @Param("now") Instant now);

    @Select("""
            SELECT status, COUNT(*) AS total
              FROM evaluation_jobs
             WHERE batch_id = #{batchId}
             GROUP BY status
            """)
    List<StatusCountRow> countByStatus(@Param("batchId") String batchId);

    /**
     * 某 Run 在某批次的 Job 终态汇总。
     *
     * <p>用 SUM 而不是取一行判断"有没有非终态"：Job 数量很小，一次聚合就能回答
     * "required 是否全部完成 / 是否有 required 失败"，避免在 Java 里拼多次查询。
     */
    @Select("""
            SELECT COUNT(*) AS total_jobs,
                   COALESCE(SUM(j.status IN ('PENDING', 'RUNNING')), 0) AS non_terminal_jobs,
                   COALESCE(SUM(i.`required` = 1), 0) AS required_jobs,
                   COALESCE(SUM(i.`required` = 1 AND j.status = 'COMPLETED'), 0) AS required_completed,
                   COALESCE(SUM(i.`required` = 1 AND j.status IN ('FAILED', 'SKIPPED')), 0) AS required_failed
              FROM evaluation_jobs j
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
             WHERE j.batch_id = #{batchId}
               AND j.run_id = #{runId}
            """)
    RunJobAggregateRow aggregateRunJobs(@Param("batchId") String batchId,
                                        @Param("runId") String runId);

    /** 批次内全部 Run 是否都已终态；批次收口用它，避免"失败的 Run 永远没有 Job"把批次卡在 RUNNING。 */
    @Select("""
            SELECT COUNT(*)
              FROM evaluation_batch_runs br
              JOIN evaluation_runs r ON r.id = br.run_id
             WHERE br.batch_id = #{batchId}
               AND r.status IN ('PENDING', 'QUEUED', 'RUNNING', 'EVALUATING')
            """)
    long countNonTerminalRuns(@Param("batchId") String batchId);

    /** 批次内 Run 数量（详情展示用）。 */
    @Select("SELECT COUNT(*) FROM evaluation_batch_runs WHERE batch_id = #{batchId}")
    long countBatchRuns(@Param("batchId") String batchId);

    /** 单条 Job 的完整投影（回调校验算子类型与 Run 归属时使用，不加锁）。 */
    @Select("""
            SELECT j.id AS job_id,
                   j.run_id,
                   j.batch_id,
                   j.evaluation_plan_version_id AS plan_version_id,
                   j.evaluation_plan_item_id AS plan_item_id,
                   d.evaluator_key,
                   d.version AS evaluator_version,
                   d.evaluator_type,
                   d.implementation_ref,
                   i.execution_order,
                   i.`required`,
                   i.config_json,
                   j.attempt_count,
                   j.input_hash
              FROM evaluation_jobs j
              JOIN evaluation_plan_items i ON i.id = j.evaluation_plan_item_id
              JOIN evaluator_definitions d ON d.id = i.evaluator_definition_id
             WHERE j.id = #{jobId}
            """)
    Optional<EvaluationJobRow> findJobDetail(@Param("jobId") String jobId);
}
