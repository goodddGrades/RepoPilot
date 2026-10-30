package com.repopilot.modules.annotation.mapper;

import com.repopilot.modules.annotation.entity.AnnotationTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code annotation_tasks} 的读写（TASK-009）。
 *
 * <p>所有状态变更都用 {@code UPDATE ... WHERE id=? AND status=? AND version=?} 的
 * 条件更新并检查影响行数：并发领取因此只有一个能成功，其余拿到 0 行并被上层转成 409。
 * 这是"后写不能覆盖前写"的数据库级保证，不依赖服务层的先读后写。
 *
 * <p>领取条件包含 {@code assignee_id IS NULL OR assignee_id = ?}：
 * 已领取的任务只能被原领取者继续操作，别人抢不走。
 */
@Mapper
public interface AnnotationTaskMapper {

    /**
     * 列清单。
     *
     * <p>刻意用普通字符串拼接而不是文本块：文本块会剥掉行尾空白，
     * {@code "SELECT " + COLUMNS} 会拼成 {@code SELECTid, ...} 这种缺空格的 SQL，
     * 而这种错误编译期发现不了，只会在运行时炸。
     */
    String COLUMNS = "id, run_id, evaluation_job_id, status, assignee_id, claimed_at,"
            + " submitted_at, reviewed_at, version, created_at, updated_at";

    @Insert("""
            INSERT INTO annotation_tasks (id, run_id, evaluation_job_id, status, assignee_id, claimed_at, submitted_at, reviewed_at, version, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{evaluationJobId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{status},
                   #{assigneeId},
                   #{claimedAt},
                   #{submittedAt},
                   #{reviewedAt},
                   #{version},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(AnnotationTask entity);

    @Select("SELECT " + COLUMNS + """
             FROM annotation_tasks
             WHERE id = #{id}
            """)
    Optional<AnnotationTask> findById(@Param("id") String id);

    /** 同一 Run 上是否已存在未终态任务，避免重复派单。 */
    @Select("""
            SELECT COUNT(*) FROM annotation_tasks
             WHERE run_id = #{runId}
               AND status NOT IN ('REVIEWED', 'CANCELLED')
            """)
    int countOpenByRun(@Param("runId") String runId);

    // 动态条件的列表查询：SELECT 前缀用普通字符串写死，避免文本块剥掉行尾空格后
    // 拼出 "SELECTid, ..." 这种编译期看不出来的坏 SQL。
    @Select("<script>"
            + "SELECT " + COLUMNS + """
              FROM annotation_tasks
             WHERE 1 = 1
             <if test="status != null"> AND status = #{status} </if>
             <if test="assigneeId != null"> AND assignee_id = #{assigneeId} </if>
             <if test="runId != null"> AND run_id = #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler} </if>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)    List<AnnotationTask> list(@Param("status") String status,
                              @Param("assigneeId") String assigneeId,
                              @Param("runId") String runId,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM annotation_tasks
             WHERE 1 = 1
             <if test="status != null"> AND status = #{status} </if>
             <if test="assigneeId != null"> AND assignee_id = #{assigneeId} </if>
             <if test="runId != null"> AND run_id = #{runId,typeHandler=com.repopilot.common.id.UlidTypeHandler} </if>
            </script>
            """)
    long count(@Param("status") String status,
               @Param("assigneeId") String assigneeId,
               @Param("runId") String runId);

    /**
     * 原子领取：仅当任务处于可领取状态、版本匹配且尚未被别人占用时生效。
     *
     * <p>{@code version = version + 1} 在同一条 UPDATE 里完成，天然避免"两个领取者
     * 都读到 version=0"。返回 1 表示领取成功，0 表示已被他人抢先或版本过期。
     */
    @Update("""
            UPDATE annotation_tasks
               SET status = 'CLAIMED',
                   assignee_id = #{assigneeId},
                   claimed_at = #{now},
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status IN ('PENDING', 'RETURNED')
               AND (assignee_id IS NULL OR assignee_id = #{assigneeId})
            """)
    int claim(@Param("id") String id,
              @Param("assigneeId") String assigneeId,
              @Param("expectedVersion") long expectedVersion,
              @Param("now") Instant now);

    /** 提交：仅当前领取者、且仍处于 CLAIMED。 */
    @Update("""
            UPDATE annotation_tasks
               SET status = 'SUBMITTED',
                   submitted_at = #{now},
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status = 'CLAIMED'
               AND assignee_id = #{assigneeId}
            """)
    int markSubmitted(@Param("id") String id,
                      @Param("assigneeId") String assigneeId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("now") Instant now);

    /** 审核通过：仅 SUBMITTED。 */
    @Update("""
            UPDATE annotation_tasks
               SET status = 'REVIEWED',
                   reviewed_at = #{now},
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status = 'SUBMITTED'
            """)
    int markReviewed(@Param("id") String id,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("now") Instant now);

    /**
     * 退回：仅 SUBMITTED。退回后仍保留 {@code assignee_id}，
     * 使原标注者可以继续处理；他人重新领取时会覆盖它。
     */
    @Update("""
            UPDATE annotation_tasks
               SET status = 'RETURNED',
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status = 'SUBMITTED'
            """)
    int markReturned(@Param("id") String id,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("now") Instant now);

    /** 取消：任何未终态（不含 REVIEWED）。 */
    @Update("""
            UPDATE annotation_tasks
               SET status = 'CANCELLED',
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'RETURNED')
            """)
    int markCancelled(@Param("id") String id,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("now") Instant now);

    /** 实验内所有待复核任务：用于「自动评测失败或结论冲突后进入人工质检」。 */
    @Select("""
            SELECT COUNT(*) FROM annotation_tasks t
              JOIN evaluation_runs r ON r.id = t.run_id
             WHERE r.experiment_id = #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND t.status IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'RETURNED')
            """)
    long countOpenByExperiment(@Param("experimentId") String experimentId);
}