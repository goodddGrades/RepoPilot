package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_plan_items} 的读写。
 *
 * <p>方案项按 {@code execution_order} 串行执行，因此两列都有唯一约束：
 * 同一版本内不允许重复算子（重复算子是配置错误，不是"换个参数再算一次"），
 * 也不允许重复顺序（顺序冲突会让"下一步是哪个"变成未定义）。
 *
 * <p>草稿编辑采用"先删后插"整体替换：逐项 diff 需要额外状态，而方案项数量很小，
 * 整体替换更不容易出现"删了一半"的中间态。
 */
@Mapper
public interface EvaluationPlanItemMapper {

    @Insert("""
            INSERT INTO evaluation_plan_items (id, plan_version_id, evaluator_definition_id, execution_order,
                                               `required`, config_json, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{planVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluatorDefinitionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{executionOrder},
                   #{required},
                   #{configJson},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationPlanItem entity);

    @Insert("""
            <script>
            INSERT INTO evaluation_plan_items (id, plan_version_id, evaluator_definition_id, execution_order,
                                               `required`, config_json, created_at, updated_at)
            VALUES
            <foreach collection="items" item="i" separator=",">
              (#{i.id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
               #{i.planVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
               #{i.evaluatorDefinitionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
               #{i.executionOrder},
               #{i.required},
               #{i.configJson},
               #{i.createdAt},
               #{i.updatedAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("items") List<EvaluationPlanItem> items);

    @Select("""
            SELECT id, plan_version_id, evaluator_definition_id, execution_order, `required`, config_json,
                   created_at, updated_at
              FROM evaluation_plan_items
             WHERE id = #{id}
            """)
    Optional<EvaluationPlanItem> findById(@Param("id") String id);

    @Select("""
            SELECT id, plan_version_id, evaluator_definition_id, execution_order, `required`, config_json,
                   created_at, updated_at
              FROM evaluation_plan_items
             WHERE plan_version_id = #{planVersionId}
             ORDER BY execution_order ASC
            """)
    List<EvaluationPlanItem> listByVersion(@Param("planVersionId") String planVersionId);

    @Delete("""
            DELETE FROM evaluation_plan_items
             WHERE plan_version_id = #{planVersionId}
            """)
    int deleteByVersion(@Param("planVersionId") String planVersionId);
}
