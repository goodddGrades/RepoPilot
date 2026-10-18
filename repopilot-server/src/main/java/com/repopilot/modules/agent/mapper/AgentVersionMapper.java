package com.repopilot.modules.agent.mapper;

import com.repopilot.modules.agent.entity.AgentVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code agent_versions} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
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
            SELECT id,
                   agent_id,
                   version,
                   runner_type,
                   config_json,
                   config_hash,
                   status,
                   published_at,
                   created_at,
                   updated_at
              FROM agent_versions
              WHERE id = #{id}
            """)
    Optional<AgentVersion> findById(@Param("id") String id);
}
