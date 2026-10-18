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
}
