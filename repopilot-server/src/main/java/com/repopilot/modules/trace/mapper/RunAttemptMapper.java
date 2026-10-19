package com.repopilot.modules.trace.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.modules.trace.entity.RunAttempt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code run_attempts} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface RunAttemptMapper {

    @Insert("""
            INSERT INTO run_attempts (id, run_id, attempt_no, worker_id, status, lease_token_hash, lease_until, deadline_at, completion_hash, output_json, output_artifact_id, usage_json, started_at, finished_at, error_code, error_message, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{attemptNo},
                   #{workerId},
                   #{status},
                   #{leaseTokenHash},
                   #{leaseUntil},
                   #{deadlineAt},
                   #{completionHash},
                   #{outputJson},
                   #{outputArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{usageJson},
                   #{startedAt},
                   #{finishedAt},
                   #{errorCode},
                   #{errorMessage},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(RunAttempt entity);

    @Select("""
            SELECT id,
                   run_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   started_at,
                   finished_at,
                   error_code,
                   error_message,
                   created_at,
                   updated_at
              FROM run_attempts
              WHERE id = #{id}
            """)
    Optional<RunAttempt> findById(@Param("id") String id);

    // ---- TASK-004：租约、续租、终态与回收 ----

    @Select("""
            SELECT id,
                   run_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   started_at,
                   finished_at,
                   error_code,
                   error_message,
                   created_at,
                   updated_at
              FROM run_attempts
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<RunAttempt> findByIdForUpdate(@Param("id") String id);

    @Update("""
            UPDATE run_attempts
               SET lease_until = #{leaseUntil},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'RUNNING'
            """)
    int updateLease(@Param("id") String id,
                    @Param("leaseUntil") Instant leaseUntil,
                    @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE run_attempts
               SET status = 'COMPLETED',
                   completion_hash = #{completionHash},
                   output_json = #{outputJson},
                   output_artifact_id = #{outputArtifactId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   usage_json = #{usageJson},
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
            """)
    int complete(@Param("id") String id,
                 @Param("completionHash") String completionHash,
                 @Param("outputJson") JsonNode outputJson,
                 @Param("outputArtifactId") String outputArtifactId,
                 @Param("usageJson") JsonNode usageJson,
                 @Param("now") Instant now);

    @Update("""
            UPDATE run_attempts
               SET status = #{status},
                   completion_hash = #{completionHash},
                   usage_json = #{usageJson},
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
            """)
    int fail(@Param("id") String id,
             @Param("status") AttemptStatus status,
             @Param("completionHash") String completionHash,
             @Param("usageJson") JsonNode usageJson,
             @Param("errorCode") String errorCode,
             @Param("errorMessage") String errorMessage,
             @Param("now") Instant now);

    @Select("""
            SELECT id,
                   run_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   started_at,
                   finished_at,
                   error_code,
                   error_message,
                   created_at,
                   updated_at
              FROM run_attempts
             WHERE run_id = #{runId}
             ORDER BY attempt_no ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RunAttempt> listByRun(@Param("runId") String runId,
                               @Param("limit") int limit,
                               @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM run_attempts WHERE run_id = #{runId}")
    long countByRun(@Param("runId") String runId);

    /**
     * 某实验全部 RunAttempt（含被重试掉的），用于报告的"平台实际执行成本"Agent 侧口径。
     *
     * <p>与最终观察成本分开：只统计被接受的那次 Attempt 会让重试开销从报告里消失，
     * 用户看到的成本会显著低于平台真实支出。
     */
    @Select("""
            SELECT a.id,
                   a.run_id,
                   a.attempt_no,
                   a.worker_id,
                   a.status,
                   a.lease_token_hash,
                   a.lease_until,
                   a.deadline_at,
                   a.completion_hash,
                   a.output_json,
                   a.output_artifact_id,
                   a.usage_json,
                   a.started_at,
                   a.finished_at,
                   a.error_code,
                   a.error_message,
                   a.created_at,
                   a.updated_at
              FROM run_attempts a
              JOIN evaluation_runs r ON r.id = a.run_id
             WHERE r.experiment_id = #{experimentId}
             ORDER BY a.run_id ASC, a.attempt_no ASC
            """)
    List<RunAttempt> listByExperiment(@Param("experimentId") String experimentId);

    /**
     * 过期租约候选（回收器用）。
     *
     * <p>不加行锁：真正的竞态由"锁 Run 行 + 条件更新"解决。这里只负责挑出候选，
     * 多实例同时扫到同一行也不会重复回收（第二辆车影响行数为 0）。
     */
    @Select("""
            SELECT id,
                   run_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   output_json,
                   output_artifact_id,
                   usage_json,
                   started_at,
                   finished_at,
                   error_code,
                   error_message,
                   created_at,
                   updated_at
              FROM run_attempts
             WHERE status = 'RUNNING'
               AND (lease_until < #{now} OR deadline_at <= #{now})
             ORDER BY lease_until ASC
             LIMIT #{limit}
            """)
    List<RunAttempt> findExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE run_attempts a
              JOIN evaluation_runs r ON r.id = a.run_id
               SET a.status = 'CANCELLED',
                   a.error_code = 'CANCELLED',
                   a.error_message = '实验已取消',
                   a.finished_at = #{now},
                   a.updated_at = #{now}
             WHERE r.experiment_id = #{experimentId}
               AND a.status = 'RUNNING'
            """)
    int cancelRunningByExperiment(@Param("experimentId") String experimentId,
                                  @Param("now") Instant now);
}
