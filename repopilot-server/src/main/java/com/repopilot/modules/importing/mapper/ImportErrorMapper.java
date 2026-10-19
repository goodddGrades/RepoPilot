package com.repopilot.modules.importing.mapper;

import com.repopilot.modules.importing.entity.ImportError;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * {@code import_errors} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ImportErrorMapper {

    @Insert("""
            INSERT INTO import_errors (id, batch_id, line_no, error_code, error_message, record_hash, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{lineNo},
                   #{errorCode},
                   #{errorMessage},
                   #{recordHash},
                   #{createdAt})
            """)
    int insert(ImportError entity);

    @Select("""
            SELECT id,
                   batch_id,
                   line_no,
                   error_code,
                   error_message,
                   record_hash,
                   created_at
              FROM import_errors
              WHERE id = #{id}
            """)
    Optional<ImportError> findById(@Param("id") String id);

    // ------------------------------------------------------------------ TASK-005

    /**
     * 幂等写入错误行。
     *
     * <p>同一行可能被重试处理（崩溃恢复、重复投递）。{@code ON DUPLICATE KEY UPDATE id = id}
     * 让第二次写入变成无操作，而不是抛唯一键异常把恢复流程再打断一次。
     * 不回显原始行内容：错误消息只描述"哪一列违反了哪条规则"。
     */
    @Insert("""
            INSERT INTO import_errors (id, batch_id, line_no, error_code, error_message, record_hash, created_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{batchId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{lineNo},
                   #{errorCode},
                   #{errorMessage},
                   #{recordHash},
                   #{createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(ImportError entity);

    @Select("""
            SELECT id,
                   batch_id,
                   line_no,
                   error_code,
                   error_message,
                   record_hash,
                   created_at
              FROM import_errors
             WHERE batch_id = #{batchId}
             ORDER BY line_no ASC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ImportError> listByBatch(@Param("batchId") String batchId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
              FROM import_errors
             WHERE batch_id = #{batchId}
            """)
    long countByBatch(@Param("batchId") String batchId);
}
