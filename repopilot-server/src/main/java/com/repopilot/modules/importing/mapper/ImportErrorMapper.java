package com.repopilot.modules.importing.mapper;

import com.repopilot.modules.importing.entity.ImportError;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
