package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
