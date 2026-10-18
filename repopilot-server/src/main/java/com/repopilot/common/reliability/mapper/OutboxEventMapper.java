package com.repopilot.common.reliability.mapper;

import com.repopilot.common.reliability.entity.OutboxEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * {@code outbox_events} 的基础读写。
 *
 * <p>只开放本任务需要的最小集合：插入与按主键读取。批量写入、条件查询与状态变更
 * 由后续 TASK 按各自用例添加，避免提前长成"什么都能做"的通用 DAO。
 * 插入语句显式列出列，生成列（如批次表的 INITIAL 标记列）不参与写入。
 */
@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload_json, status, retry_count, next_retry_at, publisher_id, lease_until, claim_token_hash, published_at, created_at, updated_at)
            VALUES (#{id,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{aggregateType},
                   #{aggregateId,typeHandler=com.repopilot.common.id.UlidTypeHandler},
                   #{eventType},
                   #{eventVersion},
                   #{payloadJson},
                   #{status},
                   #{retryCount},
                   #{nextRetryAt},
                   #{publisherId},
                   #{leaseUntil},
                   #{claimTokenHash},
                   #{publishedAt},
                   #{createdAt},
                   #{updatedAt})
            """)
    int insert(OutboxEvent entity);

    @Select("""
            SELECT id,
                   aggregate_type,
                   aggregate_id,
                   event_type,
                   event_version,
                   payload_json,
                   status,
                   retry_count,
                   next_retry_at,
                   publisher_id,
                   lease_until,
                   claim_token_hash,
                   published_at,
                   created_at,
                   updated_at
              FROM outbox_events
              WHERE id = #{id}
            """)
    Optional<OutboxEvent> findById(@Param("id") String id);
}
