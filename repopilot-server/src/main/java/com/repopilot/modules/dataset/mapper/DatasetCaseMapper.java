package com.repopilot.modules.dataset.mapper;

import com.repopilot.modules.dataset.entity.DatasetCase;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@code dataset_cases} 的读写。
 *
 * <p>批量插入用多值 {@code INSERT}：一次往返写完，配合
 * {@code rewriteBatchedStatements=true} 由驱动改写。逐条插会在 10000 行时变成
 * 10000 次网络往返，导入接口会先被超时打死。
 */
@Mapper
public interface DatasetCaseMapper {

    String COLUMNS = "id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at";

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

    /** 批量插入；调用方需保证 {@code caseKey} 在版本内唯一（校验已在写库前完成）。 */
    @Insert("""
            <script>
            INSERT INTO dataset_cases (id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at)
            VALUES
            <foreach collection="cases" item="c" separator=",">
              (#{c.id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
               #{c.datasetVersionId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
               #{c.caseKey},
               #{c.taskType},
               #{c.inputJson},
               #{c.expectedJson},
               #{c.metadataJson},
               #{c.createdAt},
               #{c.updatedAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("cases") List<DatasetCase> cases);

    @Select("""
            SELECT id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at
              FROM dataset_cases
             WHERE id = #{id}
            """)
    Optional<DatasetCase> findById(@Param("id") String id);

    /** 按 caseKey 升序分页；{@code keyword} 匹配 caseKey。 */
    @Select("""
            <script>
            SELECT id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at
              FROM dataset_cases
             WHERE dataset_version_id = #{versionId}
            <if test="keyword != null">
               AND case_key LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
            </if>
             ORDER BY case_key ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DatasetCase> listByVersion(@Param("versionId") String versionId,
                                    @Param("keyword") String keyword,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM dataset_cases
             WHERE dataset_version_id = #{versionId}
            <if test="keyword != null">
               AND case_key LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
            </if>
            </script>
            """)
    long countByVersion(@Param("versionId") String versionId, @Param("keyword") String keyword);

    /** 计算 content_hash 用：一次性取出版本内全部样本，避免边查边算导致两次结果不同。 */
    @Select("""
            SELECT id, dataset_version_id, case_key, task_type, input_json, expected_json, metadata_json, created_at, updated_at
              FROM dataset_cases
             WHERE dataset_version_id = #{versionId}
             ORDER BY case_key ASC
            """)
    List<DatasetCase> findAllByVersion(@Param("versionId") String versionId);

    /**
     * 按版本内的 caseKey 查主键。
     *
     * <p>唯一键是 (dataset_version_id, case_key)，因此这里最多返回一行；
     * 返回 id 而不是 boolean，是为了让"修改自身时 key 未变"这种情况能被区分出来。
     */
    @Select("""
            SELECT id
              FROM dataset_cases
             WHERE dataset_version_id = #{versionId}
               AND case_key = #{caseKey}
            """)
    Optional<String> findIdByVersionAndKey(@Param("versionId") String versionId,
                                           @Param("caseKey") String caseKey);

    /** 与库内已有样本的 key 冲突检查；导入必须在写库前调用。 */
    @Select("""
            <script>
            SELECT case_key
              FROM dataset_cases
             WHERE dataset_version_id = #{versionId}
               AND case_key IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<String> findExistingKeys(@Param("versionId") String versionId,
                                  @Param("keys") Collection<String> keys);

    @Update("""
            UPDATE dataset_cases
               SET case_key = #{caseKey},
                   task_type = #{taskType},
                   input_json = #{inputJson},
                   expected_json = #{expectedJson},
                   metadata_json = #{metadataJson},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int update(@Param("id") String id,
               @Param("caseKey") String caseKey,
               @Param("taskType") String taskType,
               @Param("inputJson") JsonNode inputJson,
               @Param("expectedJson") JsonNode expectedJson,
               @Param("metadataJson") JsonNode metadataJson,
               @Param("updatedAt") Instant updatedAt);

    @Delete("""
            DELETE FROM dataset_cases
             WHERE id = #{id}
            """)
    int delete(@Param("id") String id);
}
