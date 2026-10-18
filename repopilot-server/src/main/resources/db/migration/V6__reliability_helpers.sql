-- V6 可靠性辅助表（Outbox/Inbox/幂等），本任务只建表
-- 由 tools/gen_ddl_from_dict.py 依据 docs/contracts/database-schema.json 生成，请改字典后重新生成，不要手改本文件。
-- 外键策略：父表已建则内联；循环引用或父表后建的组合统一放到 V7。

-- outbox_events：事务消息，TASK-002建表，TASK-011实现发布
CREATE TABLE `outbox_events` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `aggregate_type` VARCHAR(64) NOT NULL,
  `aggregate_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `event_version` INT NOT NULL DEFAULT 1,
  `payload_json` JSON NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME(3),
  `publisher_id` VARCHAR(128),
  `lease_until` DATETIME(3),
  `claim_token_hash` CHAR(64),
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_events_status_next_retry_at_created_at_id` (`status`, `next_retry_at`, `created_at`, `id`),
  CONSTRAINT `ck_outbox_events_1` CHECK (event_version >= 1),
  CONSTRAINT `ck_outbox_events_2` CHECK (retry_count >= 0),
  CONSTRAINT `ck_outbox_events_3` CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='事务消息，TASK-002建表，TASK-011实现发布';

-- inbox_events：通知去重记录，TASK-011启用
CREATE TABLE `inbox_events` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `consumer_name` VARCHAR(64) NOT NULL,
  `event_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload_hash` CHAR(64) NOT NULL,
  `processed_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbox_events_consumer_name_event_id` (`consumer_name`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='通知去重记录，TASK-011启用';

-- api_idempotency_keys：创建与状态动作的请求幂等
CREATE TABLE `api_idempotency_keys` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `scope` VARCHAR(160) NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `resource_type` VARCHAR(64) NOT NULL,
  `resource_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `http_status` INT NOT NULL,
  `response_json` JSON NOT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_idempotency_keys_scope_idempotency_key` (`scope`, `idempotency_key`),
  KEY `idx_api_idempotency_keys_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='创建与状态动作的请求幂等';
