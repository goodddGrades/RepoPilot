package com.repopilot.modules.annotation.mapper;

import com.repopilot.modules.annotation.entity.AnnotationTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code annotation_tasks} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface AnnotationTaskMapper {

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

    @Select("""
            SELECT id,
                   run_id,
                   evaluation_job_id,
                   status,
                   assignee_id,
                   claimed_at,
                   submitted_at,
                   reviewed_at,
                   version,
                   created_at,
                   updated_at
              FROM annotation_tasks
              WHERE id = #{id}
            """)
    Optional<AnnotationTask> findById(@Param("id") String id);
}
