package com.repopilot.modules.agent.mapper;

import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.modules.agent.entity.Agent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code agents} 的读写。
 *
 * <p>插入语句显式列出列；查询显式列出列（不用 {@code SELECT *}），
 * 这样字典加列时不会让映射悄悄包含未定义字段。
 *
 * <p>{@link #findByIdForUpdate} 是版本号分配与状态变更的临界区入口：
 * 先锁身份行，再分配版本号，把并发创建串行化。
 */
@Mapper
public interface AgentMapper {

    @Insert("""
            INSERT INTO agents (id, code, name, description, agent_type, status, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{code},
                   #{name},
                   #{description},
                   #{agentType},
                   #{status},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(Agent entity);

    @Select("""
            SELECT id, code, name, description, agent_type, status, created_at, updated_at
              FROM agents
             WHERE id = #{id}
            """)
    Optional<Agent> findById(@Param("id") String id);

    /** 加行锁读取：在同一事务内用于"校验父状态 + 分配版本号"。 */
    @Select("""
            SELECT id, code, name, description, agent_type, status, created_at, updated_at
              FROM agents
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<Agent> findByIdForUpdate(@Param("id") String id);

    @Select("""
            SELECT id, code, name, description, agent_type, status, created_at, updated_at
              FROM agents
             WHERE code = #{code}
            """)
    Optional<Agent> findByCode(@Param("code") String code);

    @Select("""
            <script>
            SELECT id, code, name, description, agent_type, status, created_at, updated_at
              FROM agents
            <where>
              <if test="status != null"> AND status = #{status} </if>
              <if test="keyword != null">
                AND (code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
                     OR name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\')
              </if>
            </where>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Agent> search(@Param("status") LifecycleStatus status,
                       @Param("keyword") String keyword,
                       @Param("limit") int limit,
                       @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM agents
            <where>
              <if test="status != null"> AND status = #{status} </if>
              <if test="keyword != null">
                AND (code LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
                     OR name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\')
              </if>
            </where>
            </script>
            """)
    long count(@Param("status") LifecycleStatus status, @Param("keyword") String keyword);

    @Update("""
            UPDATE agents
               SET name = #{name},
                   description = #{description},
                   status = #{status},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int update(@Param("id") String id,
               @Param("name") String name,
               @Param("description") String description,
               @Param("status") LifecycleStatus status,
               @Param("updatedAt") Instant updatedAt);
}
