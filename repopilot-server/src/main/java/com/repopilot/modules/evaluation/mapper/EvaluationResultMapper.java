package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_results} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationResultMapper {

    @Insert("""
            INSERT INTO evaluation_results (id, job_id, job_attempt_id, verdict, score, reason, evidence_json, result_json, generated_by, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{jobId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{jobAttemptId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{verdict},
                   #{score},
                   #{reason},
                   #{evidenceJson},
                   #{resultJson},
                   #{generatedBy},
                   #{createdAt})
            """)
    int insert(EvaluationResult entity);

    @Select("""
            SELECT id,
                   job_id,
                   job_attempt_id,
                   verdict,
                   score,
                   reason,
                   evidence_json,
                   result_json,
                   generated_by,
                   created_at
              FROM evaluation_results
              WHERE id = #{id}
            """)
    Optional<EvaluationResult> findById(@Param("id") String id);

    // ================================================================== TASK-007

    /** 每个 Job 至多一条 Result（唯一键 {@code job_id}），这里按 Job 读取。 */
    @Select("""
            SELECT id,
                   job_id,
                   job_attempt_id,
                   verdict,
                   score,
                   reason,
                   evidence_json,
                   result_json,
                   generated_by,
                   created_at
              FROM evaluation_results
             WHERE job_id = #{jobId}
            """)
    Optional<EvaluationResult> findByJobId(@Param("jobId") String jobId);

    @Select("""
            <script>
            SELECT id,
                   job_id,
                   job_attempt_id,
                   verdict,
                   score,
                   reason,
                   evidence_json,
                   result_json,
                   generated_by,
                   created_at
              FROM evaluation_results
             WHERE job_id IN
               <foreach collection="jobIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<EvaluationResult> findByJobIds(@Param("jobIds") List<String> jobIds);
}
