package com.repopilot.modules.agent.mapper;

import com.repopilot.common.state.RunnerType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.agent.entity.AgentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code agent_versions} 的读写。
 *
 * <p>版本号分配必须"先锁父行、再取 MAX+1"（见 {@link #nextVersion}），
 * 唯一键 {@code uk_agent_versions_agent_id_version} 只作为最后一道防线，不当主路径。
 */
@Mapper
public interface AgentVersionMapper {

    @Insert("""
            INSERT INTO agent_versions (id, agent_id, version, runner_type, config_json, config_hash, status, published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{agentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{version},
                   #{runnerType},
                   #{configJson},
                   #{configHash},
                   #{status},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(AgentVersion entity);

    @Select("""
            SELECT id, agent_id, version, runner_type, config_json, config_hash, status, published_at, created_at, updated_at
              FROM agent_versions
             WHERE id = #{id}
            """)
    Optional<AgentVersion> findById(@Param("id") String id);

    /** 编辑与发布锁同一行：保证"检查草稿 → 写入"之间不会插入一次并发发布。 */
    @Select("""
            SELECT id, agent_id, version, runner_type, config_json, config_hash, status, published_at, created_at, updated_at
              FROM agent_versions
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<AgentVersion> findByIdForUpdate(@Param("id") String id);

    /**
     * 下一个版本号。
     *
     * <p>**调用前必须先锁父 Agent 行**，否则两个并发请求会拿到同一个号。
     * 这里不用 {@code MAX(version) OVER ()} 之类的技巧：锁父行是最直接、最可验证的串行化方式。
     */
    @Select("""
            SELECT COALESCE(MAX(version), 0) + 1
              FROM agent_versions
             WHERE agent_id = #{agentId}
            """)
    int nextVersion(@Param("agentId") String agentId);

    @Select("""
            SELECT id, agent_id, version, runner_type, config_json, config_hash, status, published_at, created_at, updated_at
              FROM agent_versions
             WHERE agent_id = #{agentId}
             ORDER BY version DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentVersion> listByAgent(@Param("agentId") String agentId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
              FROM agent_versions
             WHERE agent_id = #{agentId}
            """)
    long countByAgent(@Param("agentId") String agentId);

    @Update("""
            UPDATE agent_versions
               SET config_json = #{configJson},
                   config_hash = #{configHash},
                   runner_type = #{runnerType},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateConfig(@Param("id") String id,
                     @Param("configJson") JsonNode configJson,
                     @Param("configHash") String configHash,
                     @Param("runnerType") RunnerType runnerType,
                     @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE agent_versions
               SET status = #{status},
                   published_at = #{publishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateStatus(@Param("id") String id,
                     @Param("status") VersionStatus status,
                     @Param("publishedAt") Instant publishedAt,
                     @Param("updatedAt") Instant updatedAt);
}
