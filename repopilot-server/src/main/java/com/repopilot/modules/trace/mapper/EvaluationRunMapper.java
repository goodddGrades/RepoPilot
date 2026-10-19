package com.repopilot.modules.trace.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.trace.dto.RunListRow;
import com.repopilot.modules.trace.entity.EvaluationRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_runs} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationRunMapper {

    @Insert("""
            INSERT INTO evaluation_runs (id, experiment_id, experiment_target_id, dataset_case_id, repeat_index, import_batch_id, mode, status, task_outcome, source_system, source_record_id, source_record_hash, trace_id, external_trace_ref, output_json, output_artifact_id, usage_json, observation_metadata_json, attempt_count, active_attempt_id, accepted_attempt_id, next_retry_at, sealed_at, started_at, finished_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentTargetId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{datasetCaseId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{repeatIndex},
                   #{importBatchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{mode},
                   #{status},
                   #{taskOutcome},
                   #{sourceSystem},
                   #{sourceRecordId},
                   #{sourceRecordHash},
                   #{traceId},
                   #{externalTraceRef},
                   #{outputJson},
                   #{outputArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{usageJson},
                   #{observationMetadataJson},
                   #{attemptCount},
                   #{activeAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{acceptedAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{nextRetryAt},
                   #{sealedAt},
                   #{startedAt},
                   #{finishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationRun entity);

    @Select("""
            SELECT id,
                   experiment_id,
                   experiment_target_id,
                   dataset_case_id,
                   repeat_index,
                   import_batch_id,
                   mode,
                   status,
                   task_outcome,
                   source_system,
                   source_record_id,
                   source_record_hash,
                   trace_id,
                   external_trace_ref,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   observation_metadata_json,
                   attempt_count,
                   active_attempt_id,
                   accepted_attempt_id,
                   next_retry_at,
                   sealed_at,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_runs
              WHERE id = #{id}
            """)
    Optional<EvaluationRun> findById(@Param("id") String id);

    // ---- TASK-004：槽位生成、领取、封口、取消与聚合 ----

    /**
     * 批量插入 Run 槽位。
     *
     * <p>上限 10000 行，单条多值 INSERT 会超过 MySQL 的 65535 占位符上限，
     * 因此由调用方按 500 行分片（与 TASK-003 的数据集样本写入同一个口径）。
     */
    @Insert("""
            <script>
            INSERT INTO evaluation_runs (id, experiment_id, experiment_target_id, dataset_case_id, repeat_index, import_batch_id, mode, status, task_outcome, source_system, source_record_id, source_record_hash, trace_id, external_trace_ref, output_json, output_artifact_id, usage_json, observation_metadata_json, attempt_count, active_attempt_id, accepted_attempt_id, next_retry_at, sealed_at, started_at, finished_at, created_at, updated_at)
            VALUES
            <foreach collection="runs" item="r" separator=",">
            (#{r.id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.experimentTargetId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.datasetCaseId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.repeatIndex},
             #{r.importBatchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.mode},
             #{r.status},
             #{r.taskOutcome},
             #{r.sourceSystem},
             #{r.sourceRecordId},
             #{r.sourceRecordHash},
             #{r.traceId},
             #{r.externalTraceRef},
             #{r.outputJson},
             #{r.outputArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.usageJson},
             #{r.observationMetadataJson},
             #{r.attemptCount},
             #{r.activeAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.acceptedAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.nextRetryAt},
             #{r.sealedAt},
             #{r.startedAt},
             #{r.finishedAt},
             #{r.createdAt},
             #{r.updatedAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("runs") List<EvaluationRun> runs);

    @Select("""
            SELECT id,
                   experiment_id,
                   experiment_target_id,
                   dataset_case_id,
                   repeat_index,
                   import_batch_id,
                   mode,
                   status,
                   task_outcome,
                   source_system,
                   source_record_id,
                   source_record_hash,
                   trace_id,
                   external_trace_ref,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   observation_metadata_json,
                   attempt_count,
                   active_attempt_id,
                   accepted_attempt_id,
                   next_retry_at,
                   sealed_at,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_runs
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<EvaluationRun> findByIdForUpdate(@Param("id") String id);

    /**
     * 领取候选（**不加锁**）。
     *
     * <p>为什么领取拆成"先取候选、再逐条加锁"：带 {@code ORDER BY ... LIMIT} 的
     * {@code SELECT ... FOR UPDATE} 会先把扫描到的行全部锁住再排序取前 N 条
     * （filesort 阶段就要加锁），于是一个 Worker 会把自己不返回的行也锁上，
     * 并发的其他 Worker 用 {@code SKIP LOCKED} 就全部跳过、领不到任何任务。
     * 候选查询只负责缩小范围，真正的抢占与条件重校验在 {@link #lockClaimCandidate} 里完成。
     */
    @Select("""
            <script>
            SELECT r.id
              FROM evaluation_runs r
              JOIN experiments e ON e.id = r.experiment_id
              JOIN experiment_targets t ON t.id = r.experiment_target_id
              JOIN agent_versions av ON av.id = t.agent_version_id
             WHERE e.status IN ('QUEUED', 'RUNNING')
               AND r.active_attempt_id IS NULL
               AND (r.status = 'QUEUED'
                    OR (r.status = 'RUNNING' AND r.next_retry_at IS NOT NULL AND r.next_retry_at &lt;= #{now}))
               AND av.runner_type IN
               <foreach collection="runnerTypes" item="rt" open="(" separator="," close=")">#{rt}</foreach>
             ORDER BY r.created_at ASC, r.id ASC
             LIMIT #{limit}
            </script>
            """)
    List<String> listClaimCandidates(@Param("runnerTypes") List<String> runnerTypes,
                                     @Param("now") Instant now,
                                     @Param("limit") int limit);

    /**
     * 对单个候选加锁并**重新校验**全部领取条件。
     *
     * <p>这是领取的唯一权威判定：{@code FOR UPDATE OF r SKIP LOCKED} 保证同一 Run 只会被一个
     * Worker 抢到；条件里重新带上实验状态，因此"候选查询之后实验被取消"不会继续发出任务。
     * 被其他 Worker 抢先锁住时返回空集合，由调用方试下一个候选。
     */
    @Select("""
            <script>
            SELECT r.id
              FROM evaluation_runs r
              JOIN experiments e ON e.id = r.experiment_id
              JOIN experiment_targets t ON t.id = r.experiment_target_id
              JOIN agent_versions av ON av.id = t.agent_version_id
             WHERE r.id = #{id}
               AND e.status IN ('QUEUED', 'RUNNING')
               AND r.active_attempt_id IS NULL
               AND (r.status = 'QUEUED'
                    OR (r.status = 'RUNNING' AND r.next_retry_at IS NOT NULL AND r.next_retry_at &lt;= #{now}))
               AND av.runner_type IN
               <foreach collection="runnerTypes" item="rt" open="(" separator="," close=")">#{rt}</foreach>
               FOR UPDATE OF r SKIP LOCKED
            </script>
            """)
    Optional<String> lockClaimCandidate(@Param("id") String id,
                                        @Param("runnerTypes") List<String> runnerTypes,
                                        @Param("now") Instant now);

    /** PENDING → QUEUED：提交事务后即便进程崩溃，重启后的扫描也能继续推进。 */
    @Update("""
            UPDATE evaluation_runs r
              JOIN experiments e ON e.id = r.experiment_id
               SET r.status = 'QUEUED',
                   r.updated_at = #{updatedAt}
             WHERE r.status = 'PENDING'
               AND r.mode = 'ONLINE'
               AND e.status IN ('QUEUED', 'RUNNING')
            """)
    int promotePending(@Param("updatedAt") Instant updatedAt);

    /** 可重试的 Run 到点后重新可领取（保持 RUNNING，只清空 next_retry_at 前的可见性）。 */
    @Update("""
            UPDATE evaluation_runs r
              JOIN experiments e ON e.id = r.experiment_id
               SET r.status = 'QUEUED',
                   r.next_retry_at = NULL,
                   r.updated_at = #{updatedAt}
             WHERE r.status = 'RUNNING'
               AND r.active_attempt_id IS NULL
               AND r.next_retry_at IS NOT NULL
               AND r.next_retry_at <= #{now}
               AND e.status IN ('QUEUED', 'RUNNING')
            """)
    int promoteRetries(@Param("now") Instant now, @Param("updatedAt") Instant updatedAt);

    /**
     * 状态推进为 RUNNING 并挂上 active Attempt。
     *
     * <p>{@code WHERE status IN ('QUEUED','RUNNING') AND active_attempt_id IS NULL} 是 fencing 的一半：
     * 若另一领取者已挂上 Attempt，这里影响行数为 0，调用方回滚整个事务。
     */
    @Update("""
            UPDATE evaluation_runs
               SET status = 'RUNNING',
                   active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   attempt_count = #{attemptCount},
                   next_retry_at = NULL,
                   started_at = COALESCE(started_at, #{startedAt}),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status IN ('QUEUED', 'RUNNING')
               AND active_attempt_id IS NULL
            """)
    int markRunning(@Param("id") String id,
                    @Param("attemptId") String attemptId,
                    @Param("attemptCount") int attemptCount,
                    @Param("startedAt") Instant startedAt,
                    @Param("updatedAt") Instant updatedAt);

    /**
     * 成功封口：Run 转 EVALUATING，锁定 accepted Attempt 并清空 active Attempt。
     *
     * <p>条件里带 {@code active_attempt_id = 本次 Attempt}：被回收器接管后旧 Worker 的完成回调
     * 影响行数为 0，不会把别人的结果写成自己的。
     */
    @Update("""
            UPDATE evaluation_runs
               SET status = 'EVALUATING',
                   task_outcome = #{taskOutcome},
                   output_json = #{outputJson},
                   output_artifact_id = #{outputArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   usage_json = #{usageJson},
                   observation_metadata_json = #{observationMetadataJson},
                   accepted_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   sealed_at = #{now},
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int sealSuccess(@Param("id") String id,
                    @Param("attemptId") String attemptId,
                    @Param("taskOutcome") TaskOutcome taskOutcome,
                    @Param("outputJson") JsonNode outputJson,
                    @Param("outputArtifactId") String outputArtifactId,
                    @Param("usageJson") JsonNode usageJson,
                    @Param("observationMetadataJson") JsonNode observationMetadataJson,
                    @Param("now") Instant now);

    /** 失败终态。条件同样要求 active Attempt 匹配。 */
    @Update("""
            UPDATE evaluation_runs
               SET status = #{status},
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status IN ('RUNNING', 'QUEUED')
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int markTerminal(@Param("id") String id,
                     @Param("attemptId") String attemptId,
                     @Param("status") RunStatus status,
                     @Param("now") Instant now);

    /** 可重试失败：Run 保持 RUNNING，清空 active Attempt 并写下次可领取时间。 */
    @Update("""
            UPDATE evaluation_runs
               SET active_attempt_id = NULL,
                   next_retry_at = #{nextRetryAt},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
               AND active_attempt_id = #{attemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
            """)
    int releaseForRetry(@Param("id") String id,
                        @Param("attemptId") String attemptId,
                        @Param("nextRetryAt") Instant nextRetryAt,
                        @Param("now") Instant now);

    @Update("""
            UPDATE evaluation_runs
               SET status = 'CANCELLED',
                   active_attempt_id = NULL,
                   next_retry_at = NULL,
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE experiment_id = #{experimentId}
               AND status IN ('PENDING', 'QUEUED', 'RUNNING', 'EVALUATING')
            """)
    int cancelNonTerminalByExperiment(@Param("experimentId") String experimentId,
                                      @Param("now") Instant now);

    @Select("""
            SELECT COUNT(*)
              FROM evaluation_runs
             WHERE experiment_id = #{experimentId}
               AND status IN ('PENDING', 'QUEUED', 'RUNNING', 'EVALUATING')
            """)
    long countNonTerminal(@Param("experimentId") String experimentId);

    @Select("""
            SELECT COUNT(*)
              FROM evaluation_runs
             WHERE experiment_id = #{experimentId}
               AND status = 'COMPLETED'
            """)
    long countCompleted(@Param("experimentId") String experimentId);

    @Select("""
            SELECT COUNT(*)
              FROM evaluation_runs
             WHERE experiment_id = #{experimentId}
            """)
    long countByExperiment(@Param("experimentId") String experimentId);

    @Select("""
            <script>
            SELECT r.id,
                   r.experiment_id,
                   r.experiment_target_id,
                   c.case_key,
                   t.target_key,
                   r.dataset_case_id,
                   r.repeat_index,
                   r.mode,
                   r.status,
                   r.task_outcome,
                   r.attempt_count,
                   r.accepted_attempt_id,
                   r.sealed_at,
                   r.started_at,
                   r.finished_at,
                   r.created_at
              FROM evaluation_runs r
              JOIN dataset_cases c ON c.id = r.dataset_case_id
              JOIN experiment_targets t ON t.id = r.experiment_target_id
             WHERE r.experiment_id = #{experimentId}
            <if test="targetId != null"> AND r.experiment_target_id = #{targetId} </if>
            <if test="caseKey != null"> AND c.case_key = #{caseKey} </if>
            <if test="status != null"> AND r.status = #{status} </if>
            <if test="taskOutcome != null"> AND r.task_outcome = #{taskOutcome} </if>
             ORDER BY r.created_at ASC, r.id ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<com.repopilot.modules.trace.dto.RunListRow> search(@Param("experimentId") String experimentId,
                                                            @Param("targetId") String targetId,
                                                            @Param("caseKey") String caseKey,
                                                            @Param("status") RunStatus status,
                                                            @Param("taskOutcome") TaskOutcome taskOutcome,
                                                            @Param("limit") int limit,
                                                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM evaluation_runs r
              JOIN dataset_cases c ON c.id = r.dataset_case_id
             WHERE r.experiment_id = #{experimentId}
            <if test="targetId != null"> AND r.experiment_target_id = #{targetId} </if>
            <if test="caseKey != null"> AND c.case_key = #{caseKey} </if>
            <if test="status != null"> AND r.status = #{status} </if>
            <if test="taskOutcome != null"> AND r.task_outcome = #{taskOutcome} </if>
            </script>
            """)
    long count(@Param("experimentId") String experimentId,
               @Param("targetId") String targetId,
               @Param("caseKey") String caseKey,
               @Param("status") RunStatus status,
               @Param("taskOutcome") TaskOutcome taskOutcome);

    // ---- TASK-005：离线导入的幂等查找与离线启动 ----

    /**
     * 同一来源身份的历史 Run。
     *
     * <p>身份是 {@code (experiment, target, sourceSystem, sourceRecordId)}，由数据库唯一键兜底。
     * 再导入同一条记录时用它判断"同正文复用"还是"异正文冲突"。
     */
    @Select("""
            SELECT id,
                   experiment_id,
                   experiment_target_id,
                   dataset_case_id,
                   repeat_index,
                   import_batch_id,
                   mode,
                   status,
                   task_outcome,
                   source_system,
                   source_record_id,
                   source_record_hash,
                   trace_id,
                   external_trace_ref,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   observation_metadata_json,
                   attempt_count,
                   active_attempt_id,
                   accepted_attempt_id,
                   next_retry_at,
                   sealed_at,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_runs
             WHERE experiment_id = #{experimentId}
               AND experiment_target_id = #{targetId}
               AND source_system = #{sourceSystem}
               AND source_record_id = #{sourceRecordId}
            """)
    Optional<EvaluationRun> findBySourceIdentity(@Param("experimentId") String experimentId,
                                                 @Param("targetId") String targetId,
                                                 @Param("sourceSystem") String sourceSystem,
                                                 @Param("sourceRecordId") String sourceRecordId);

    /** 槽位占用者：同一 Target 下 (case, repeat) 只允许一条 Run。 */
    @Select("""
            SELECT id,
                   experiment_id,
                   experiment_target_id,
                   dataset_case_id,
                   repeat_index,
                   import_batch_id,
                   mode,
                   status,
                   task_outcome,
                   source_system,
                   source_record_id,
                   source_record_hash,
                   trace_id,
                   external_trace_ref,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   observation_metadata_json,
                   attempt_count,
                   active_attempt_id,
                   accepted_attempt_id,
                   next_retry_at,
                   sealed_at,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_runs
             WHERE experiment_target_id = #{targetId}
               AND dataset_case_id = #{caseId}
               AND repeat_index = #{repeatIndex}
            """)
    Optional<EvaluationRun> findBySlot(@Param("targetId") String targetId,
                                       @Param("caseId") String caseId,
                                       @Param("repeatIndex") int repeatIndex);

    /** 实验内全部离线 Run（按创建顺序），启动时冻结批次清单。 */
    @Select("""
            SELECT id,
                   experiment_id,
                   experiment_target_id,
                   dataset_case_id,
                   repeat_index,
                   import_batch_id,
                   mode,
                   status,
                   task_outcome,
                   source_system,
                   source_record_id,
                   source_record_hash,
                   trace_id,
                   external_trace_ref,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   observation_metadata_json,
                   attempt_count,
                   active_attempt_id,
                   accepted_attempt_id,
                   next_retry_at,
                   sealed_at,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_runs
             WHERE experiment_id = #{experimentId}
             ORDER BY created_at ASC, id ASC
            """)
    List<EvaluationRun> listByExperiment(@Param("experimentId") String experimentId);

    /**
     * 离线 Run 进入评测：PENDING→EVALUATING。
     *
     * <p>条件更新带旧状态：并发启动或已被取消时影响行数为 0，调用方据此回滚整个事务，
     * 不留下"实验已 QUEUED、Run 仍在 PENDING"的半截状态。
     */
    @Update("""
            UPDATE evaluation_runs
               SET status = 'EVALUATING',
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'PENDING'
               AND mode = 'OFFLINE'
            """)
    int markEvaluating(@Param("id") String id, @Param("updatedAt") Instant updatedAt);
}
