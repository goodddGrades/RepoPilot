package com.repopilot.modules.trace.mapper;

import com.repopilot.modules.trace.entity.RunAttempt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
