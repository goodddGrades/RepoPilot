package com.repopilot.modules.dataset.mapper;

import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code dataset_versions} 的读写。
 *
 * <p>发布动作只允许改 {@code status / content_hash / published_at / updated_at}：
 * 归档不允许清空 {@code content_hash}，否则"归档后仍可查历史内容摘要"这条就断了。
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
            SELECT id, dataset_id, version, status, description, content_hash, published_at, created_at, updated_at
              FROM dataset_versions
             WHERE id = #{id}
            """)
    Optional<DatasetVersion> findById(@Param("id") String id);

    @Select("""
            SELECT id, dataset_id, version, status, description, content_hash, published_at, created_at, updated_at
              FROM dataset_versions
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<DatasetVersion> findByIdForUpdate(@Param("id") String id);

    @Select("""
            SELECT COALESCE(MAX(version), 0) + 1
              FROM dataset_versions
             WHERE dataset_id = #{datasetId}
            """)
    int nextVersion(@Param("datasetId") String datasetId);

    @Select("""
            SELECT id, dataset_id, version, status, description, content_hash, published_at, created_at, updated_at
              FROM dataset_versions
             WHERE dataset_id = #{datasetId}
             ORDER BY version DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<DatasetVersion> listByDataset(@Param("datasetId") String datasetId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
              FROM dataset_versions
             WHERE dataset_id = #{datasetId}
            """)
    long countByDataset(@Param("datasetId") String datasetId);

    @Update("""
            UPDATE dataset_versions
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

    /** 样本增删改后刷新版本时间戳；草稿没有内容摘要，不写 content_hash。 */
    @Update("""
            UPDATE dataset_versions
               SET updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int touch(@Param("id") String id, @Param("updatedAt") Instant updatedAt);
}
