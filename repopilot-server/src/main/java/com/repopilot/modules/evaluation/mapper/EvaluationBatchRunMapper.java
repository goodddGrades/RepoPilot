package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationBatchRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_batch_runs} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationBatchRunMapper {

    @Insert("""
            INSERT INTO evaluation_batch_runs (batch_id, run_id, experiment_id, created_at)
            VALUES (#{batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{createdAt})
            """)
    int insert(EvaluationBatchRun entity);

    @Select("""
            SELECT batch_id,
                   run_id,
                   experiment_id,
                   created_at
              FROM evaluation_batch_runs
              WHERE batch_id = #{batchId}
                      AND run_id = #{runId}
            """)
    Optional<EvaluationBatchRun> findById(@Param("batchId") String batchId, @Param("runId") String runId);

    /**
     * 批次固定 Run 集合。
     *
     * <p>这张表是"批次到底包含哪些 Run"的唯一真相；写入与批次行同事务，
     * 提交后不再增删（TASK-004 只写 INITIAL，重评批次留给后续任务）。
     */
    @Insert("""
            <script>
            INSERT INTO evaluation_batch_runs (batch_id, run_id, experiment_id, created_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
            (#{r.batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
             #{r.createdAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("rows") List<EvaluationBatchRun> rows);

    @Select("""
            SELECT batch_id, run_id, experiment_id, created_at
              FROM evaluation_batch_runs
             WHERE batch_id = #{batchId}
             ORDER BY run_id ASC
            """)
    List<EvaluationBatchRun> listByBatch(@Param("batchId") String batchId);
}
