package com.repopilot.modules.evaluation.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_job_attempts} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationJobAttemptMapper {

    @Insert("""
            INSERT INTO evaluation_job_attempts (id, job_id, attempt_no, worker_id, status, lease_token_hash, lease_until, deadline_at, completion_hash, result_json, usage_json, error_code, error_message, started_at, finished_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{jobId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{attemptNo},
                   #{workerId},
                   #{status},
                   #{leaseTokenHash},
                   #{leaseUntil},
                   #{deadlineAt},
                   #{completionHash},
                   #{resultJson},
                   #{usageJson},
                   #{errorCode},
                   #{errorMessage},
                   #{startedAt},
                   #{finishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationJobAttempt entity);

    @Select("""
            SELECT id,
                   job_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   result_json,
                   usage_json,
                   error_code,
                   error_message,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_job_attempts
              WHERE id = #{id}
            """)
    Optional<EvaluationJobAttempt> findById(@Param("id") String id);

    // ================================================================== TASK-007

    /**
     * 锁定 Attempt。
     *
     * <p>回调路径先锁 Attempt，再锁 Job，顺序与在线执行一致：反过来的话，
     * "回收器判定过期"与"Worker 提交完成"会在两把锁上互相等待。
     */
    @Select("""
            SELECT id,
                   job_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   result_json,
                   usage_json,
                   error_code,
                   error_message,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_job_attempts
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<EvaluationJobAttempt> lockById(@Param("id") String id);

    @Update("""
            UPDATE evaluation_job_attempts
               SET lease_until = #{leaseUntil},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
            """)
    int renewLease(@Param("id") String id,
                   @Param("leaseUntil") Instant leaseUntil,
                   @Param("now") Instant now);

    /**
     * 完成 Attempt。
     *
     * <p>{@code completion_hash} 是幂等重放的依据：同正文重放返回原回执，
     * 异正文 409。没有它，"完成响应丢失后重发"会变成第二次业务写入。
     */
    @Update("""
            UPDATE evaluation_job_attempts
               SET status = 'COMPLETED',
                   completion_hash = #{completionHash},
                   result_json = #{resultJson},
                   usage_json = #{usageJson},
                   finished_at = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND status = 'RUNNING'
            """)
    int markCompleted(@Param("id") String id,
                      @Param("completionHash") String completionHash,
                      @Param("resultJson") JsonNode resultJson,
                      @Param("usageJson") JsonNode usageJson,
                      @Param("now") Instant now);

    /**
     * 失败 Attempt。{@code completion_hash} 对失败回调同样要保存：
     * 同一正文重放时返回原回执，而不是再走一次"是否重试"的决策。
     */
    @Update("""
            UPDATE evaluation_job_attempts
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
    int markFailed(@Param("id") String id,
                   @Param("status") AttemptStatus status,
                   @Param("completionHash") String completionHash,
                   @Param("usageJson") JsonNode usageJson,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") Instant now);

    /** 回收器：租约或硬截止已过的 RUNNING Attempt。 */
    @Select("""
            SELECT id,
                   job_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   result_json,
                   usage_json,
                   error_code,
                   error_message,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_job_attempts
             WHERE status = 'RUNNING'
               AND (lease_until < #{now} OR deadline_at < #{now})
             ORDER BY lease_until ASC, id ASC
             LIMIT #{limit}
            """)
    List<EvaluationJobAttempt> findExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE evaluation_job_attempts a
              JOIN evaluation_jobs j ON j.id = a.job_id
               SET a.status = 'CANCELLED',
                   a.finished_at = #{now},
                   a.updated_at = #{now}
             WHERE j.batch_id = #{batchId}
               AND a.status = 'RUNNING'
            """)
    int cancelRunningByBatch(@Param("batchId") String batchId, @Param("now") Instant now);

    @Select("""
            SELECT id,
                   job_id,
                   attempt_no,
                   worker_id,
                   status,
                   lease_token_hash,
                   lease_until,
                   deadline_at,
                   completion_hash,
                   result_json,
                   usage_json,
                   error_code,
                   error_message,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM evaluation_job_attempts
             WHERE job_id = #{jobId}
             ORDER BY attempt_no ASC
            """)
    List<EvaluationJobAttempt> listByJob(@Param("jobId") String jobId);

    // ================================================================== TASK-010

    /**
     * 某批次下全部 Job Attempt（含重试），用于报告的"平台实际执行成本"口径。
     *
     * <p>报告必须把"最终观察成本"（只算被接受的那次Attempt）和"平台实际执行成本"
     * （所有 RunAttempt 与 JobAttempt，含被重试掉的那些）分开，否则等于告诉用户
     * 一次实验只花了成功那次的费用，重试与 Judge 的开销全部消失。
     */
    @Select("""
            SELECT a.id,
                   a.job_id,
                   a.attempt_no,
                   a.worker_id,
                   a.status,
                   a.lease_token_hash,
                   a.lease_until,
                   a.deadline_at,
                   a.completion_hash,
                   a.result_json,
                   a.usage_json,
                   a.error_code,
                   a.error_message,
                   a.started_at,
                   a.finished_at,
                   a.created_at,
                   a.updated_at
              FROM evaluation_job_attempts a
              JOIN evaluation_jobs j ON j.id = a.job_id
             WHERE j.batch_id = #{batchId}
             ORDER BY a.job_id ASC, a.attempt_no ASC
            """)
    List<EvaluationJobAttempt> listByBatch(@Param("batchId") String batchId);
}
