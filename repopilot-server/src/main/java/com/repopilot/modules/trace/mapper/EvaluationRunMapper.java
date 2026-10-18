package com.repopilot.modules.trace.mapper;

import com.repopilot.modules.trace.entity.EvaluationRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
