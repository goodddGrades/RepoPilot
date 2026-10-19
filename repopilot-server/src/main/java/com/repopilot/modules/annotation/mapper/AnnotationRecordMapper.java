package com.repopilot.modules.annotation.mapper;

import com.repopilot.modules.annotation.entity.AnnotationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code annotation_records} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface AnnotationRecordMapper {

    @Insert("""
            INSERT INTO annotation_records (id, annotation_task_id, operator_id, action, verdict, failure_category, reason, evidence_json, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{annotationTaskId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{operatorId},
                   #{action},
                   #{verdict},
                   #{failureCategory},
                   #{reason},
                   #{evidenceJson},
                   #{createdAt})
            """)
    int insert(AnnotationRecord entity);

    @Select("""
            SELECT id,
                   annotation_task_id,
                   operator_id,
                   action,
                   verdict,
                   failure_category,
                   reason,
                   evidence_json,
                   created_at
              FROM annotation_records
             WHERE id = #{id}
            """)
    Optional<AnnotationRecord> findById(@Param("id") String id);

    /**
     * 任务的全部历史记录，按时间正序。
     *
     * <p>用 {@code (created_at, id)} 而非只有 {@code created_at} 排序：同一毫秒内
     * 可能有多条记录，没有稳定的次级排序时回放会闪烁。
     */
    @Select("""
            SELECT id,
                   annotation_task_id,
                   operator_id,
                   action,
                   verdict,
                   failure_category,
                   reason,
                   evidence_json,
                   created_at
              FROM annotation_records
             WHERE annotation_task_id = #{taskId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
             ORDER BY created_at ASC, id ASC
            """)
    java.util.List<AnnotationRecord> listByTask(
            @Param("taskId") String taskId);

    /** 同一操作者对同一任务的动作是否已存在：用于幂等重放时不追加重复记录。 */
    @Select("""
            SELECT COUNT(*) FROM annotation_records
             WHERE annotation_task_id = #{taskId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND action = #{action}
               AND operator_id = #{operatorId}
               AND (#{verdict} IS NULL OR verdict = #{verdict})
               AND (#{failureCategory} IS NULL OR failure_category = #{failureCategory})
            """)
    int countMatching(@Param("taskId") String taskId,
                      @Param("operatorId") String operatorId,
                      @Param("action") String action,
                      @Param("verdict") String verdict,
                      @Param("failureCategory") String failureCategory);

    /** 任务最新的有效审核记录：REVIEWED 视图只取它，不使用未审核草稿。 */
    @Select("""
            SELECT id,
                   annotation_task_id,
                   operator_id,
                   action,
                   verdict,
                   failure_category,
                   reason,
                   evidence_json,
                   created_at
              FROM annotation_records
             WHERE annotation_task_id = #{taskId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND action = 'REVIEW'
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    Optional<AnnotationRecord> findLatestReview(
            @Param("taskId") String taskId);
}
