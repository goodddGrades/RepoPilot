package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code evaluator_definitions} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface EvaluatorDefinitionMapper {

    @Insert("""
            INSERT INTO evaluator_definitions (id, evaluator_key, version, display_name, description, evaluator_type, implementation_ref, input_schema_json, output_schema_json, status, published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluatorKey},
                   #{version},
                   #{displayName},
                   #{description},
                   #{evaluatorType},
                   #{implementationRef},
                   #{inputSchemaJson},
                   #{outputSchemaJson},
                   #{status},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluatorDefinition entity);

    @Select("""
            SELECT id,
                   evaluator_key,
                   version,
                   display_name,
                   description,
                   evaluator_type,
                   implementation_ref,
                   input_schema_json,
                   output_schema_json,
                   status,
                   published_at,
                   created_at,
                   updated_at
              FROM evaluator_definitions
              WHERE id = #{id}
            """)
    Optional<EvaluatorDefinition> findById(@Param("id") String id);
}
