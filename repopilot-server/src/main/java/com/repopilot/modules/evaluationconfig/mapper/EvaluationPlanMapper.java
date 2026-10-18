package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_plans} 的读写。
 *
 * <p>方案身份没有 status 列：可停用的是"版本"，不是方案本身。
 * 因此这里不提供归档，创建版本时也不需要检查父状态。
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
            SELECT id, code, name, description, created_at, updated_at
              FROM evaluation_plans
             WHERE id = #{id}
            """)
    Optional<EvaluationPlan> findById(@Param("id") String id);

    /** 版本号分配的临界区入口：先锁方案行，再取 MAX(version)+1。 */
    @Select("""
            SELECT id, code, name, description, created_at, updated_at
              FROM evaluation_plans
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<EvaluationPlan> findByIdForUpdate(@Param("id") String id);

    @Select("""
            <script>
            SELECT id, code, name, description, created_at, updated_at
              FROM evaluation_plans
            <where>
              <if test="keyword != null">
                AND (code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
                     OR name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\')
              </if>
            </where>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EvaluationPlan> search(@Param("keyword") String keyword,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM evaluation_plans
            <where>
              <if test="keyword != null">
                AND (code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
                     OR name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\')
              </if>
            </where>
            </script>
            """)
    long count(@Param("keyword") String keyword);
}
