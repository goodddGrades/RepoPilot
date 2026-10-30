package com.repopilot.common.reliability.mapper;

import com.repopilot.common.reliability.entity.ApiIdempotencyKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code api_idempotency_keys} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface ApiIdempotencyKeyMapper {

    @Insert("""
            INSERT INTO api_idempotency_keys (id, scope, idempotency_key, request_hash, resource_type, resource_id, http_status, response_json, expires_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{scope},
                   #{idempotencyKey},
                   #{requestHash},
                   #{resourceType},
                   #{resourceId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{httpStatus},
                   #{responseJson},
                   #{expiresAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(ApiIdempotencyKey entity);

    @Select("""
            SELECT id,
                   scope,
                   idempotency_key,
                   request_hash,
                   resource_type,
                   resource_id,
                   http_status,
                   response_json,
                   expires_at,
                   created_at,
                   updated_at
              FROM api_idempotency_keys
              WHERE id = #{id}
            """)
    Optional<ApiIdempotencyKey> findById(@Param("id") String id);

    /**
     * 按 (scope, key) 查回执。
     *
     * <p>调用方必须用 {@code REQUIRES_NEW} 打开新事务再调它：并发场景下要读到**别人已提交**的回执，
     * 在当前事务的快照里读不到刚提交的数据。
     */
    @Select("""
            SELECT id,
                   scope,
                   idempotency_key,
                   request_hash,
                   resource_type,
                   resource_id,
                   http_status,
                   response_json,
                   expires_at,
                   created_at,
                   updated_at
              FROM api_idempotency_keys
              WHERE scope = #{scope}
                AND idempotency_key = #{idempotencyKey}
            """)
    Optional<ApiIdempotencyKey> findByScopeAndKey(@Param("scope") String scope,
                                                  @Param("idempotencyKey") String idempotencyKey);
}
