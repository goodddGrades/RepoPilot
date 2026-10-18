package com.repopilot.modules.dataset.mapper;

import com.repopilot.modules.dataset.entity.DatasetVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code dataset_versions} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface DatasetVersionMapper {

    @Insert("""
            INSERT INTO dataset_versions (id, dataset_id, version, status, description, content_hash, published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{datasetId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{version},
                   #{status},
                   #{description},
                   #{contentHash},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(DatasetVersion entity);

    @Select("""
            SELECT id,
                   dataset_id,
                   version,
                   status,
                   description,
                   content_hash,
                   published_at,
                   created_at,
                   updated_at
              FROM dataset_versions
              WHERE id = #{id}
            """)
    Optional<DatasetVersion> findById(@Param("id") String id);
}
