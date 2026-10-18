package com.repopilot.modules.evaluation.mapper;

import com.repopilot.modules.evaluation.entity.EvaluationResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
