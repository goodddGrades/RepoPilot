package com.repopilot.modules.experiment.mapper;

import com.repopilot.modules.experiment.entity.ExperimentTarget;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code experiment_targets} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ExperimentTargetMapper {

    @Insert("""
            INSERT INTO experiment_targets (id, experiment_id, target_key, display_name, agent_version_id, repeat_count, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{targetKey},
                   #{displayName},
                   #{agentVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{repeatCount},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(ExperimentTarget entity);

    @Select("""
            SELECT id,
                   experiment_id,
                   target_key,
                   display_name,
                   agent_version_id,
                   repeat_count,
                   created_at,
                   updated_at
              FROM experiment_targets
              WHERE id = #{id}
            """)
    Optional<ExperimentTarget> findById(@Param("id") String id);

    // ---- TASK-004：实验目标读取 ----

    @Select("""
            SELECT id,
                   experiment_id,
                   target_key,
                   display_name,
                   agent_version_id,
                   repeat_count,
                   created_at,
                   updated_at
              FROM experiment_targets
             WHERE experiment_id = #{experimentId}
             ORDER BY target_key ASC
            """)
    List<ExperimentTarget> listByExperiment(@Param("experimentId") String experimentId);

    @Select("""
            SELECT id,
                   experiment_id,
                   target_key,
                   display_name,
                   agent_version_id,
                   repeat_count,
                   created_at,
                   updated_at
              FROM experiment_targets
             WHERE experiment_id = #{experimentId}
               AND target_key = #{targetKey}
            """)
    Optional<ExperimentTarget> findByKey(@Param("experimentId") String experimentId,
                                         @Param("targetKey") String targetKey);
}
