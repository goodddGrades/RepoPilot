package com.repopilot.modules.experiment.mapper;

import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.RunMode;
import com.repopilot.modules.experiment.entity.Experiment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
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

    // ---- TASK-004：草稿、提交、取消与聚合 ----

    /**
     * 行锁读取。
     *
     * <p>开始/取消/聚合都先锁实验行：实验级状态是整个执行管道的闸门，
     * 如果只锁 Run 行，取消与领取可能各自成功一半。
     */
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
               FOR UPDATE
            """)
    Optional<Experiment> findByIdForUpdate(@Param("id") String id);

    @Select("""
            <script>
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
            <where>
              <if test="status != null"> AND status = #{status} </if>
              <if test="mode != null"> AND mode = #{mode} </if>
              <if test="keyword != null">
                AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
              </if>
            </where>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Experiment> search(@Param("status") ExperimentStatus status,
                            @Param("mode") RunMode mode,
                            @Param("keyword") String keyword,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM experiments
            <where>
              <if test="status != null"> AND status = #{status} </if>
              <if test="mode != null"> AND mode = #{mode} </if>
              <if test="keyword != null">
                AND name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\\\'
              </if>
            </where>
            </script>
            """)
    long count(@Param("status") ExperimentStatus status,
               @Param("mode") RunMode mode,
               @Param("keyword") String keyword);

    /** DRAFT → QUEUED。条件更新失败返回 0，由服务层抛 409（不静默覆盖）。 */
    @Update("""
            UPDATE experiments
               SET status = 'QUEUED',
                   expected_run_count = #{expectedRunCount},
                   started_at = #{startedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'DRAFT'
            """)
    int markQueued(@Param("id") String id,
                   @Param("expectedRunCount") long expectedRunCount,
                   @Param("startedAt") Instant startedAt,
                   @Param("updatedAt") Instant updatedAt);

    /**
     * QUEUED → RUNNING：第一个任务被 Worker 领走即视为开始执行。
     *
     * <p>条件更新只对 QUEUED 生效：实验已被取消时影响行数为 0，领取照常返回任务，
     * 由后续回调的租约校验把这次执行拒掉，而不是让领取失败、任务卡在库里。
     * 不写 {@code finished_at}——这里不是终态。
     */
    @Update("""
            UPDATE experiments
               SET status = 'RUNNING',
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'QUEUED'
            """)
    int markRunning(@Param("id") String id, @Param("updatedAt") Instant updatedAt);

    /**
     * 带前置状态的条件更新。
     *
     * <p>聚合与取消都走这里：{@code WHERE status = expected} 让"读到旧状态再写"的竞态
     * 变成影响行数 0，而不是把已经 CANCELLED 的实验改回 FAILED。
     */
    @Update("""
            UPDATE experiments
               SET status = #{status},
                   finished_at = #{finishedAt},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = #{expected}
            """)
    int updateStatus(@Param("id") String id,
                     @Param("expected") ExperimentStatus expected,
                     @Param("status") ExperimentStatus status,
                     @Param("finishedAt") Instant finishedAt,
                     @Param("updatedAt") Instant updatedAt);
}
