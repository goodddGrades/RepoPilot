package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
