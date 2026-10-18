package com.repopilot.modules.dataset.mapper;

import com.repopilot.modules.dataset.entity.DatasetCase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code dataset_cases} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface DatasetCaseMapper {

    @Insert("""
            INSERT INTO dataset_cases (id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{datasetVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{caseKey},
                   #{taskType},
                   #{inputJson},
                   #{expectedJson},
                   #{metadataJson},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(DatasetCase entity);

    @Select("""
            SELECT id,
                   dataset_version_id,
                   case_key,
                   task_type,
                   input_json,
                   expected_json,
                   metadata_json,
                   created_at,
                   updated_at
              FROM dataset_cases
              WHERE id = #{id}
            """)
    Optional<DatasetCase> findById(@Param("id") String id);
}
