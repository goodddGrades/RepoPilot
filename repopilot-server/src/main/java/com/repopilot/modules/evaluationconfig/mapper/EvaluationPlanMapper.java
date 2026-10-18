package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code evaluation_plans} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluationPlanMapper {

    @Insert("""
            INSERT INTO evaluation_plans (id, code, name, description, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{code},
                   #{name},
                   #{description},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationPlan entity);

    @Select("""
            SELECT id,
                   code,
                   name,
                   description,
                   created_at,
                   updated_at
              FROM evaluation_plans
              WHERE id = #{id}
            """)
    Optional<EvaluationPlan> findById(@Param("id") String id);
}
