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

    // ---- TASK-010：报告的"已审核标注视图" ----

    /**
     * 某实验下全部已审核通过的人工结论。
     *
     * <p>只取 {@code action='REVIEW'} 的记录：SUBMIT 是标注者草拟的判断，
     * 未经审核就当作结论用，等于跳过复核流程把未审意见升格成结论。
     *
     * <p>返回每条记录的 slot key（Run 级或 Job 级），由服务层决定怎么覆盖自动结论。
     * 这里不做"取最新一条"的聚合：同一任务可能有多次 REVIEW 通过（退回再提交），
     * 哪一条算最终应由服务层按时间明确挑出，而不是在 SQL 里隐式决定。
     *
     * <p>{@code annotation_tasks} 上没有 {@code experiment_id}——它只记复核对象
     * （Run / Job），实验归属要经 {@code evaluation_runs} 才能拿到。
     */
    @Select("""
            SELECT ar.id,
                   ar.annotation_task_id,
                   ar.operator_id,
                   ar.action,
                   ar.verdict,
                   ar.failure_category,
                   ar.reason,
                   ar.evidence_json,
                   ar.created_at,
                   t.run_id AS slot_run_id,
                   t.evaluation_job_id AS slot_job_id
              FROM annotation_records ar
              JOIN annotation_tasks t ON t.id = ar.annotation_task_id
              JOIN evaluation_runs r ON r.id = t.run_id
             WHERE r.experiment_id = #{experimentId,typeHandler=com.repopilot.common.id.UlidTypeHandler}
               AND ar.action = 'REVIEW'
               AND ar.verdict IS NOT NULL
             ORDER BY t.run_id ASC, ar.created_at ASC, ar.id ASC
            """)
    java.util.List<ReviewedVerdictRow> listReviewedVerdictsOfExperiment(
            @Param("experimentId") String experimentId);

    /** 已审核人工结论的读模型：带槽位归属，供报告定位覆盖对象。 */
    class ReviewedVerdictRow {

        public String id;
        public String annotationTaskId;
        public String operatorId;
        public String action;
        public String verdict;
        public String failureCategory;
        public String reason;
        public com.fasterxml.jackson.databind.JsonNode evidenceJson;
        public java.time.Instant createdAt;
        /** 标注任务所属Run；Run 级复核任务用它，Job 级复核时它只是范围。 */
        public String slotRunId;
        /** 绑定的 Job；Run 级复核任务为 null。 */
        public String slotJobId;
    }
}
