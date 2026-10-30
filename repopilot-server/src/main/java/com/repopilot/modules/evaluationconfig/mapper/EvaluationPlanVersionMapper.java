package com.repopilot.modules.evaluationconfig.mapper;

import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code evaluation_plan_versions} 的读写。
 *
 * <p>草稿的 config 与 items 可以整体替换；发布后 {@code content_hash} 与 {@code published_at}
 * 一旦写入就不再变动，归档也不清空——历史批次要靠这两个值解释"这份结果由哪版配置产生"。
 */
@Mapper
public interface EvaluationPlanVersionMapper {

    @Insert("""
            INSERT INTO evaluation_plan_versions (id, plan_id, version, status, config_json, content_hash,
                                                  published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{planId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{version},
                   #{status},
                   #{configJson},
                   #{contentHash},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(EvaluationPlanVersion entity);

    @Select("""
            SELECT id, plan_id, version, status, config_json, content_hash, published_at, created_at, updated_at
              FROM evaluation_plan_versions
             WHERE id = #{id}
            """)
    Optional<EvaluationPlanVersion> findById(@Param("id") String id);

    @Select("""
            SELECT id, plan_id, version, status, config_json, content_hash, published_at, created_at, updated_at
              FROM evaluation_plan_versions
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<EvaluationPlanVersion> findByIdForUpdate(@Param("id") String id);

    @Select("""
            SELECT COALESCE(MAX(version), 0) + 1
              FROM evaluation_plan_versions
             WHERE plan_id = #{planId}
            """)
    int nextVersion(@Param("planId") String planId);

    @Select("""
            SELECT id, plan_id, version, status, config_json, content_hash, published_at, created_at, updated_at
              FROM evaluation_plan_versions
             WHERE plan_id = #{planId}
             ORDER BY version DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<EvaluationPlanVersion> listByPlan(@Param("planId") String planId,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
              FROM evaluation_plan_versions
             WHERE plan_id = #{planId}
            """)
    long countByPlan(@Param("planId") String planId);

    /** 整体替换草稿的 config；items 由 {@code EvaluationPlanItemMapper} 先删后插。 */
    @Update("""
            UPDATE evaluation_plan_versions
               SET config_json = #{configJson},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateConfig(@Param("id") String id,
                     @Param("configJson") com.fasterxml.jackson.databind.JsonNode configJson,
                     @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE evaluation_plan_versions
               SET status = #{status},
                   content_hash = #{contentHash},
                   published_at = #{publishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateStatus(@Param("id") String id,
                     @Param("status") VersionStatus status,
                     @Param("contentHash") String contentHash,
                     @Param("publishedAt") Instant publishedAt,
                     @Param("updatedAt") Instant updatedAt);
}
