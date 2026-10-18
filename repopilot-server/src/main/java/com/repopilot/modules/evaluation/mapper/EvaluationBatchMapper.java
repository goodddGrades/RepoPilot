package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
