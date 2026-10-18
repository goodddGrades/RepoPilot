package com.repopilot.modules.experiment.mapper;

import com.repopilot.modules.experiment.entity.Experiment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code experiments} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ExperimentMapper {

    @Insert("""
            INSERT INTO experiments (id, name, description, mode, dataset_version_id, evaluation_plan_version_id, status, config_json, expected_run_count, started_at, finished_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{name},
                   #{description},
                   #{mode},
                   #{datasetVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluationPlanVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{status},
                   #{configJson},
                   #{expectedRunCount},
                   #{startedAt},
                   #{finishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(Experiment entity);

    @Select("""
            SELECT id,
                   name,
                   description,
                   mode,
                   dataset_version_id,
                   evaluation_plan_version_id,
                   status,
                   config_json,
                   expected_run_count,
                   started_at,
                   finished_at,
                   created_at,
                   updated_at
              FROM experiments
              WHERE id = #{id}
            """)
    Optional<Experiment> findById(@Param("id") String id);
}
