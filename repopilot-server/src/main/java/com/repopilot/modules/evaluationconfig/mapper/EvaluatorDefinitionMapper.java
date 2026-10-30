package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.common.state.EvaluatorType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluator_definitions} 的读写。
 *
 * <p>算子定义由注册清单（{@code seed/evaluators.json}）幂等导入，不通过 HTTP 任意上传：
 * 算子会执行代码或调用模型，开放上传等于开放执行入口。
 *
 * <p>已发布的定义不可修改：{@link #findByKeyAndVersion} 供导入器比对，
 * 内容有变化时只告警不覆盖——改契约必须升版本，否则"同一 (key, version) 两个行为"就无法解释历史结果。
 */
@Mapper
public interface EvaluatorDefinitionMapper {

    @Insert("""
            INSERT INTO evaluator_definitions (id, evaluator_key, version, display_name, description, evaluator_type,
                                               implementation_ref, input_schema_json, output_schema_json, status,
                                               published_at, created_at, updated_at)
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
            SELECT id, evaluator_key, version, display_name, description, evaluator_type, implementation_ref,
                   input_schema_json, output_schema_json, status, published_at, created_at, updated_at
              FROM evaluator_definitions
             WHERE id = #{id}
            """)
    Optional<EvaluatorDefinition> findById(@Param("id") String id);

    @Select("""
            SELECT id, evaluator_key, version, display_name, description, evaluator_type, implementation_ref,
                   input_schema_json, output_schema_json, status, published_at, created_at, updated_at
              FROM evaluator_definitions
             WHERE evaluator_key = #{evaluatorKey}
               AND version = #{version}
            """)
    Optional<EvaluatorDefinition> findByKeyAndVersion(@Param("evaluatorKey") String evaluatorKey,
                                                      @Param("version") int version);

    /** 方案发布时批量取算子状态，避免逐条查询。 */
    @Select("""
            <script>
            SELECT id, evaluator_key, version, display_name, description, evaluator_type, implementation_ref,
                   input_schema_json, output_schema_json, status, published_at, created_at, updated_at
              FROM evaluator_definitions
             WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<EvaluatorDefinition> findByIds(@Param("ids") Collection<String> ids);

    @Select("""
            <script>
            SELECT id, evaluator_key, version, display_name, description, evaluator_type, implementation_ref,
                   input_schema_json, output_schema_json, status, published_at, created_at, updated_at
              FROM evaluator_definitions
            <where>
              <if test="key != null"> AND evaluator_key = #{key} </if>
              <if test="type != null"> AND evaluator_type = #{type} </if>
              <if test="status != null"> AND status = #{status} </if>
            </where>
             ORDER BY evaluator_key ASC, version DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EvaluatorDefinition> search(@Param("key") String key,
                                     @Param("type") EvaluatorType type,
                                     @Param("status") VersionStatus status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM evaluator_definitions
            <where>
              <if test="key != null"> AND evaluator_key = #{key} </if>
              <if test="type != null"> AND evaluator_type = #{type} </if>
              <if test="status != null"> AND status = #{status} </if>
            </where>
            </script>
            """)
    long count(@Param("key") String key,
               @Param("type") EvaluatorType type,
               @Param("status") VersionStatus status);

    /**
     * 已发布的算子 key 集合（TASK-007 能力校验使用）。
     *
     * <p>Worker 声明 {@code EVAL:xxx} 时，key 必须是这里的一员：拼错的能力如果被静默接受，
     * Worker 会一直"在线但领不到任务"，而实验看起来只是卡住。
     */
    @Select("""
            SELECT DISTINCT evaluator_key
              FROM evaluator_definitions
             WHERE status = 'PUBLISHED'
             ORDER BY evaluator_key
            """)
    List<String> listPublishedKeys();
}
