package com.repopilot.modules.dataset.mapper;

import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.modules.dataset.entity.Dataset;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code datasets} 的读写。
 *
 * <p>{@link #findByIdForUpdate} 是版本号分配的临界区入口：先锁数据集行，
 * 再取 {@code MAX(version)+1}，把并发创建串行化。
 */
@Mapper
public interface DatasetMapper {

    @Insert("""
            INSERT INTO datasets (id, code, name, description, status, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{code},
                   #{name},
                   #{description},
                   #{status},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(Dataset entity);

    @Select("""
            SELECT id, code, name, description, status, created_at, updated_at
              FROM datasets
             WHERE id = #{id}
            """)
    Optional<Dataset> findById(@Param("id") String id);

    @Select("""
            SELECT id, code, name, description, status, created_at, updated_at
              FROM datasets
             WHERE id = #{id}
               FOR UPDATE
            """)
    Optional<Dataset> findByIdForUpdate(@Param("id") String id);

    @Select("""
            <script>
            SELECT id, code, name, description, status, created_at, updated_at
              FROM datasets
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
    List<Dataset> search(@Param("status") LifecycleStatus status,
                         @Param("keyword") String keyword,
                         @Param("limit") int limit,
                         @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM datasets
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
            UPDATE datasets
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
