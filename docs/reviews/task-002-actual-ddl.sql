-- ============================================================================
-- RepoPilot 实际数据库 DDL（由真实 MySQL 8.0.22 导出，非手写）
-- ----------------------------------------------------------------------------
-- 来源：空库 repopilot_ddl_probe -> Flyway V1(空基线)..V7 -> mysqldump --no-data
-- 用途：作为 TASK-002 数据层验收证据；与 docs/contracts/database-schema.json
--       生成的迁移文件逐条对应。
-- 说明：
--   1. 本文件不含 flyway_schema_history（Flyway 自身元数据表）。
--   2. CHECK 约束中的 _utf8mb4 前缀与成对括号由 MySQL 内部规范化产生，
--      SchemaDictionaryIT 使用 canonical 归一化后与字典逐字符比对。
--   3. 所有字符集排序规则、生成列、唯一键、外键均为数据库实际对象。
-- 统计：28 表 / 28 主键 / 32 唯一键 / 46 二级索引 / 47 外键 / 60 CHECK
-- 索引名合计：106（28 PRIMARY + 32 UNIQUE + 46 二级），无隐式索引
-- ============================================================================

CREATE TABLE `agent_versions` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `agent_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` int NOT NULL,
  `runner_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `config_json` json NOT NULL,
  `config_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `published_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_versions_agent_id_version` (`agent_id`,`version`),
  CONSTRAINT `fk_agent_versions_agent_id` FOREIGN KEY (`agent_id`) REFERENCES `agents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_versions_1` CHECK ((`version` >= 1)),
  CONSTRAINT `ck_agent_versions_2` CHECK ((`runner_type` in (_utf8mb4'MOCK',_utf8mb4'CODEX',_utf8mb4'CUSTOM'))),
  CONSTRAINT `ck_agent_versions_3` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布后不可变的执行配置';

CREATE TABLE `agents` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `description` text COLLATE utf8mb4_bin,
  `agent_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agents_code` (`code`),
  CONSTRAINT `ck_agents_1` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Agent身份，不持有运行密钥';

CREATE TABLE `annotation_records` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `annotation_task_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator_id` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `action` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `verdict` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL,
  `failure_category` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `reason` text COLLATE utf8mb4_bin,
  `evidence_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_annotation_records_annotation_task_id_created_at_id` (`annotation_task_id`,`created_at`,`id`),
  CONSTRAINT `fk_annotation_records_annotation_task_id` FOREIGN KEY (`annotation_task_id`) REFERENCES `annotation_tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_annotation_records_1` CHECK ((`action` in (_utf8mb4'CLAIM',_utf8mb4'SUBMIT',_utf8mb4'REVIEW',_utf8mb4'RETURN',_utf8mb4'CANCEL'))),
  CONSTRAINT `ck_annotation_records_2` CHECK ((`verdict` in (_utf8mb4'PASS',_utf8mb4'FAIL',_utf8mb4'INDETERMINATE',_utf8mb4'NOT_APPLICABLE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='追加式人工操作历史';

CREATE TABLE `annotation_tasks` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_job_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `assignee_id` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
  `claimed_at` datetime(3) DEFAULT NULL,
  `submitted_at` datetime(3) DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_annotation_tasks_status_created_at_id` (`status`,`created_at`,`id`),
  KEY `idx_annotation_tasks_run_id_evaluation_job_id` (`run_id`,`evaluation_job_id`),
  CONSTRAINT `fk_annotation_tasks_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annotation_tasks_run_id_evaluation_job_id` FOREIGN KEY (`run_id`, `evaluation_job_id`) REFERENCES `evaluation_jobs` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_annotation_tasks_1` CHECK ((`version` >= 0)),
  CONSTRAINT `ck_annotation_tasks_2` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'CLAIMED',_utf8mb4'SUBMITTED',_utf8mb4'REVIEWED',_utf8mb4'RETURNED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='人工复核当前状态';

CREATE TABLE `api_idempotency_keys` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `scope` varchar(160) COLLATE utf8mb4_bin NOT NULL,
  `idempotency_key` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `request_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `resource_type` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `resource_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `http_status` int NOT NULL,
  `response_json` json NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_idempotency_keys_scope_idempotency_key` (`scope`,`idempotency_key`),
  KEY `idx_api_idempotency_keys_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='创建与状态动作的请求幂等';

CREATE TABLE `artifacts` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `artifact_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `storage_provider` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `object_key` varchar(512) COLLATE utf8mb4_bin NOT NULL,
  `content_type` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `size_bytes` bigint NOT NULL,
  `sha256` char(64) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'READY',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_artifacts_run_id` (`run_id`),
  CONSTRAINT `fk_artifacts_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_artifacts_1` CHECK ((`size_bytes` >= 0)),
  CONSTRAINT `ck_artifacts_2` CHECK ((`status` in (_utf8mb4'READY',_utf8mb4'DELETED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对象存储元数据，可先上传后关联Run';

CREATE TABLE `dataset_cases` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dataset_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `case_key` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `task_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `input_json` json NOT NULL,
  `expected_json` json DEFAULT NULL,
  `metadata_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_cases_dataset_version_id_case_key` (`dataset_version_id`,`case_key`),
  CONSTRAINT `fk_dataset_cases_dataset_version_id` FOREIGN KEY (`dataset_version_id`) REFERENCES `dataset_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='版本内样本身份';

CREATE TABLE `dataset_versions` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dataset_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` int NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `description` text COLLATE utf8mb4_bin,
  `content_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_versions_dataset_id_version` (`dataset_id`,`version`),
  CONSTRAINT `fk_dataset_versions_dataset_id` FOREIGN KEY (`dataset_id`) REFERENCES `datasets` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_dataset_versions_1` CHECK ((`version` >= 1)),
  CONSTRAINT `ck_dataset_versions_2` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布后固定样本集合';

CREATE TABLE `datasets` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `description` text COLLATE utf8mb4_bin,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_datasets_code` (`code`),
  CONSTRAINT `ck_datasets_1` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='数据集身份';

CREATE TABLE `evaluation_batch_runs` (
  `batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`batch_id`,`run_id`),
  KEY `idx_evaluation_batch_runs_batch_id_experiment_id` (`batch_id`,`experiment_id`),
  KEY `idx_evaluation_batch_runs_run_id_experiment_id` (`run_id`,`experiment_id`),
  CONSTRAINT `fk_evaluation_batch_runs_batch_id_experiment_id` FOREIGN KEY (`batch_id`, `experiment_id`) REFERENCES `evaluation_batches` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_batch_runs_run_id_experiment_id` FOREIGN KEY (`run_id`, `experiment_id`) REFERENCES `evaluation_runs` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='批次固定登记的Run集合；主键即(batch_id, run_id)，无独立生命周期';

CREATE TABLE `evaluation_batches` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_no` int NOT NULL,
  `trigger_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `parent_batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `initial_experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (if((`trigger_type` = _utf8mb4'INITIAL'),`experiment_id`,NULL)) STORED,
  `selection_json` json NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `expected_job_count` bigint NOT NULL DEFAULT '0',
  `created_by` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_batches_experiment_id_batch_no` (`experiment_id`,`batch_no`),
  UNIQUE KEY `uk_evaluation_batches_id_evaluation_plan_version_id` (`id`,`evaluation_plan_version_id`),
  UNIQUE KEY `uk_evaluation_batches_id_experiment_id` (`id`,`experiment_id`),
  UNIQUE KEY `uk_evaluation_batches_initial_experiment_id` (`initial_experiment_id`),
  KEY `idx_evaluation_batches_experiment_id_status_created_at` (`experiment_id`,`status`,`created_at`),
  KEY `idx_evaluation_batches_status_created_at_id` (`status`,`created_at`,`id`),
  KEY `idx_evaluation_batches_evaluation_plan_version_id` (`evaluation_plan_version_id`),
  KEY `idx_evaluation_batches_parent_batch_id_experiment_id` (`parent_batch_id`,`experiment_id`),
  CONSTRAINT `fk_evaluation_batches_evaluation_plan_version_id` FOREIGN KEY (`evaluation_plan_version_id`) REFERENCES `evaluation_plan_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_batches_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_batches_parent_batch_id_experiment_id` FOREIGN KEY (`parent_batch_id`, `experiment_id`) REFERENCES `evaluation_batches` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_batches_1` CHECK ((`batch_no` >= 1)),
  CONSTRAINT `ck_evaluation_batches_2` CHECK ((`expected_job_count` >= 0)),
  CONSTRAINT `ck_evaluation_batches_3` CHECK ((`trigger_type` in (_utf8mb4'INITIAL',_utf8mb4'REEVALUATION',_utf8mb4'MANUAL'))),
  CONSTRAINT `ck_evaluation_batches_4` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'PARTIAL_FAILED',_utf8mb4'FAILED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `ck_evaluation_batches_5` CHECK (((`trigger_type` <> _utf8mb4'INITIAL') or (`parent_batch_id` is null))),
  CONSTRAINT `ck_evaluation_batches_6` CHECK (((`parent_batch_id` is null) or (`parent_batch_id` <> `id`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='一次对待评测Run集合按固定方案版本的批量评测；Run集合由evaluation_batch_runs固定，重评创建新批次；每实验最多一个INITIAL批次由生成列唯一键强制';

CREATE TABLE `evaluation_job_attempts` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `attempt_no` int NOT NULL,
  `worker_id` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'RUNNING',
  `lease_token_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `lease_until` datetime(3) NOT NULL,
  `deadline_at` datetime(3) NOT NULL,
  `completion_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `result_json` json DEFAULT NULL,
  `usage_json` json DEFAULT NULL,
  `error_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_bin DEFAULT NULL,
  `started_at` datetime(3) NOT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_job_attempts_job_id_attempt_no` (`job_id`,`attempt_no`),
  UNIQUE KEY `uk_evaluation_job_attempts_job_id_id` (`job_id`,`id`),
  KEY `idx_evaluation_job_attempts_status_lease_until` (`status`,`lease_until`),
  CONSTRAINT `fk_evaluation_job_attempts_job_id` FOREIGN KEY (`job_id`) REFERENCES `evaluation_jobs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_job_attempts_1` CHECK ((`attempt_no` >= 1)),
  CONSTRAINT `ck_evaluation_job_attempts_2` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'TIMED_OUT',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='算子重试历史与租约';

CREATE TABLE `evaluation_jobs` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_item_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT '0',
  `active_attempt_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `next_retry_at` datetime(3) DEFAULT NULL,
  `input_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `error_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_jobs_batch_id_run_id_evaluation_plan_item_id` (`batch_id`,`run_id`,`evaluation_plan_item_id`),
  UNIQUE KEY `uk_evaluation_jobs_run_id_id` (`run_id`,`id`),
  KEY `idx_evaluation_jobs_status_next_retry_at_created_at_id` (`status`,`next_retry_at`,`created_at`,`id`),
  KEY `idx_evaluation_jobs_batch_id_evaluation_plan_version_id` (`batch_id`,`evaluation_plan_version_id`),
  KEY `idx_evaluation_jobs_evaluation_plan_version_id_evaluation_ca06b0` (`evaluation_plan_version_id`,`evaluation_plan_item_id`),
  KEY `idx_evaluation_jobs_id_active_attempt_id` (`id`,`active_attempt_id`),
  CONSTRAINT `fk_evaluation_jobs_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `evaluation_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_batch_id_evaluation_plan_version_id` FOREIGN KEY (`batch_id`, `evaluation_plan_version_id`) REFERENCES `evaluation_batches` (`id`, `evaluation_plan_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_batch_id_run_id` FOREIGN KEY (`batch_id`, `run_id`) REFERENCES `evaluation_batch_runs` (`batch_id`, `run_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_evaluation_plan_version_id_evaluation_dd5897` FOREIGN KEY (`evaluation_plan_version_id`, `evaluation_plan_item_id`) REFERENCES `evaluation_plan_items` (`plan_version_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_id_active_attempt_id` FOREIGN KEY (`id`, `active_attempt_id`) REFERENCES `evaluation_job_attempts` (`job_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_jobs_1` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `ck_evaluation_jobs_2` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'SKIPPED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='批次内Run×方案项的逻辑评测任务；Run必须已登记在evaluation_batch_runs';

CREATE TABLE `evaluation_plan_items` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `plan_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluator_definition_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `execution_order` int NOT NULL,
  `required` tinyint(1) NOT NULL DEFAULT '1',
  `config_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_id` (`plan_version_id`,`id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_evaluator_definition_id` (`plan_version_id`,`evaluator_definition_id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_execution_order` (`plan_version_id`,`execution_order`),
  KEY `idx_evaluation_plan_items_evaluator_definition_id` (`evaluator_definition_id`),
  CONSTRAINT `fk_evaluation_plan_items_evaluator_definition_id` FOREIGN KEY (`evaluator_definition_id`) REFERENCES `evaluator_definitions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_plan_items_plan_version_id` FOREIGN KEY (`plan_version_id`) REFERENCES `evaluation_plan_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_plan_items_1` CHECK ((`execution_order` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='顺序执行的固定算子配置';

CREATE TABLE `evaluation_plan_versions` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `plan_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` int NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `config_json` json NOT NULL,
  `content_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plan_versions_plan_id_version` (`plan_id`,`version`),
  CONSTRAINT `fk_evaluation_plan_versions_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `evaluation_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_plan_versions_1` CHECK ((`version` >= 1)),
  CONSTRAINT `ck_evaluation_plan_versions_2` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='方案配置快照';

CREATE TABLE `evaluation_plans` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `description` text COLLATE utf8mb4_bin,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plans_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='评测方案身份';

CREATE TABLE `evaluation_results` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_attempt_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `verdict` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `score` decimal(6,5) DEFAULT NULL,
  `reason` text COLLATE utf8mb4_bin,
  `evidence_json` json DEFAULT NULL,
  `result_json` json NOT NULL,
  `generated_by` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_results_job_id` (`job_id`),
  KEY `idx_evaluation_results_job_id_job_attempt_id` (`job_id`,`job_attempt_id`),
  CONSTRAINT `fk_evaluation_results_job_id` FOREIGN KEY (`job_id`) REFERENCES `evaluation_jobs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_results_job_id_job_attempt_id` FOREIGN KEY (`job_id`, `job_attempt_id`) REFERENCES `evaluation_job_attempts` (`job_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_results_1` CHECK (((`score` is null) or ((`score` >= 0) and (`score` <= 1)))),
  CONSTRAINT `ck_evaluation_results_2` CHECK ((`verdict` in (_utf8mb4'PASS',_utf8mb4'FAIL',_utf8mb4'INDETERMINATE',_utf8mb4'NOT_APPLICABLE'))),
  CONSTRAINT `ck_evaluation_results_3` CHECK ((`generated_by` in (_utf8mb4'RULE',_utf8mb4'TEST',_utf8mb4'LLM',_utf8mb4'MOCK')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每个Job唯一最终自动结论';

CREATE TABLE `evaluation_runs` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_target_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dataset_case_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `repeat_index` int NOT NULL DEFAULT '1',
  `import_batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `mode` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `task_outcome` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'UNKNOWN',
  `source_system` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `source_record_id` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
  `source_record_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `trace_id` char(32) COLLATE utf8mb4_bin DEFAULT NULL,
  `external_trace_ref` varchar(256) COLLATE utf8mb4_bin DEFAULT NULL,
  `output_json` json DEFAULT NULL,
  `output_artifact_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `usage_json` json DEFAULT NULL,
  `observation_metadata_json` json NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `active_attempt_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `accepted_attempt_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `next_retry_at` datetime(3) DEFAULT NULL,
  `sealed_at` datetime(3) DEFAULT NULL,
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_runs_experiment_target_id_dataset_case_id_r_1145d2` (`experiment_target_id`,`dataset_case_id`,`repeat_index`),
  UNIQUE KEY `uk_evaluation_runs_id_experiment_id` (`id`,`experiment_id`),
  UNIQUE KEY `uk_evaluation_runs_experiment_id_experiment_target_id_sou_4bbd9a` (`experiment_id`,`experiment_target_id`,`source_system`,`source_record_id`),
  KEY `idx_evaluation_runs_experiment_id_status` (`experiment_id`,`status`),
  KEY `idx_evaluation_runs_status_next_retry_at_created_at_id` (`status`,`next_retry_at`,`created_at`,`id`),
  KEY `idx_evaluation_runs_dataset_case_id` (`dataset_case_id`),
  KEY `idx_evaluation_runs_created_at_id` (`created_at`,`id`),
  KEY `idx_evaluation_runs_trace_id` (`trace_id`),
  KEY `idx_evaluation_runs_id_active_attempt_id` (`id`,`active_attempt_id`),
  KEY `idx_evaluation_runs_id_accepted_attempt_id` (`id`,`accepted_attempt_id`),
  KEY `idx_evaluation_runs_output_artifact_id` (`output_artifact_id`),
  KEY `idx_evaluation_runs_import_batch_id` (`import_batch_id`),
  CONSTRAINT `fk_evaluation_runs_dataset_case_id` FOREIGN KEY (`dataset_case_id`) REFERENCES `dataset_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_experiment_id_experiment_target_id` FOREIGN KEY (`experiment_id`, `experiment_target_id`) REFERENCES `experiment_targets` (`experiment_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_id_accepted_attempt_id` FOREIGN KEY (`id`, `accepted_attempt_id`) REFERENCES `run_attempts` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_id_active_attempt_id` FOREIGN KEY (`id`, `active_attempt_id`) REFERENCES `run_attempts` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_import_batch_id` FOREIGN KEY (`import_batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_runs_output_artifact_id` FOREIGN KEY (`output_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_evaluation_runs_1` CHECK ((`repeat_index` >= 1)),
  CONSTRAINT `ck_evaluation_runs_2` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `ck_evaluation_runs_3` CHECK (((`source_system` is null) = (`source_record_id` is null))),
  CONSTRAINT `ck_evaluation_runs_4` CHECK (((`output_json` is null) or (`output_artifact_id` is null))),
  CONSTRAINT `ck_evaluation_runs_5` CHECK ((`mode` in (_utf8mb4'ONLINE',_utf8mb4'OFFLINE'))),
  CONSTRAINT `ck_evaluation_runs_6` CHECK ((`task_outcome` in (_utf8mb4'UNKNOWN',_utf8mb4'SUCCESS',_utf8mb4'FAILURE',_utf8mb4'NOT_APPLICABLE'))),
  CONSTRAINT `ck_evaluation_runs_7` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'EVALUATING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'TIMED_OUT',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='逻辑观察与最终接受输出';

CREATE TABLE `evaluator_definitions` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluator_key` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `version` int NOT NULL,
  `display_name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `description` text COLLATE utf8mb4_bin,
  `evaluator_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `implementation_ref` varchar(256) COLLATE utf8mb4_bin NOT NULL,
  `input_schema_json` json NOT NULL,
  `output_schema_json` json NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `published_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluator_definitions_evaluator_key_version` (`evaluator_key`,`version`),
  CONSTRAINT `ck_evaluator_definitions_1` CHECK ((`version` >= 1)),
  CONSTRAINT `ck_evaluator_definitions_2` CHECK ((`evaluator_type` in (_utf8mb4'RULE',_utf8mb4'TRACE',_utf8mb4'COST',_utf8mb4'TEST',_utf8mb4'LLM_JUDGE'))),
  CONSTRAINT `ck_evaluator_definitions_3` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'ARCHIVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='算子实现与输入输出契约版本';

CREATE TABLE `experiment_targets` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `target_key` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `display_name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `agent_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `repeat_count` int NOT NULL DEFAULT '1',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_experiment_targets_experiment_id_target_key` (`experiment_id`,`target_key`),
  UNIQUE KEY `uk_experiment_targets_experiment_id_id` (`experiment_id`,`id`),
  KEY `idx_experiment_targets_agent_version_id` (`agent_version_id`),
  CONSTRAINT `fk_experiment_targets_agent_version_id` FOREIGN KEY (`agent_version_id`) REFERENCES `agent_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_experiment_targets_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_experiment_targets_1` CHECK ((`repeat_count` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='在线/离线比较对象';

CREATE TABLE `experiments` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `description` text COLLATE utf8mb4_bin,
  `mode` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `dataset_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `config_json` json NOT NULL,
  `expected_run_count` bigint NOT NULL DEFAULT '0',
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_experiments_status_created_at_id` (`status`,`created_at`,`id`),
  KEY `idx_experiments_dataset_version_id` (`dataset_version_id`),
  KEY `idx_experiments_evaluation_plan_version_id` (`evaluation_plan_version_id`),
  CONSTRAINT `fk_experiments_dataset_version_id` FOREIGN KEY (`dataset_version_id`) REFERENCES `dataset_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_experiments_evaluation_plan_version_id` FOREIGN KEY (`evaluation_plan_version_id`) REFERENCES `evaluation_plan_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_experiments_1` CHECK ((`expected_run_count` >= 0)),
  CONSTRAINT `ck_experiments_2` CHECK ((`mode` in (_utf8mb4'ONLINE',_utf8mb4'OFFLINE'))),
  CONSTRAINT `ck_experiments_3` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'PARTIAL_FAILED',_utf8mb4'FAILED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='固定版本的实验';

CREATE TABLE `import_batches` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `import_type` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `dataset_version_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `file_artifact_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `total_count` bigint NOT NULL DEFAULT '0',
  `success_count` bigint NOT NULL DEFAULT '0',
  `failed_count` bigint NOT NULL DEFAULT '0',
  `duplicate_count` bigint NOT NULL DEFAULT '0',
  `last_line_no` bigint NOT NULL DEFAULT '0',
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_import_batches_experiment_id_status` (`experiment_id`,`status`),
  KEY `idx_import_batches_status_created_at_id` (`status`,`created_at`,`id`),
  KEY `idx_import_batches_dataset_version_id` (`dataset_version_id`),
  KEY `idx_import_batches_file_artifact_id` (`file_artifact_id`),
  CONSTRAINT `fk_import_batches_dataset_version_id` FOREIGN KEY (`dataset_version_id`) REFERENCES `dataset_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_batches_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_batches_file_artifact_id` FOREIGN KEY (`file_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_import_batches_1` CHECK ((`total_count` >= 0)),
  CONSTRAINT `ck_import_batches_2` CHECK ((`success_count` >= 0)),
  CONSTRAINT `ck_import_batches_3` CHECK ((`failed_count` >= 0)),
  CONSTRAINT `ck_import_batches_4` CHECK ((`duplicate_count` >= 0)),
  CONSTRAINT `ck_import_batches_5` CHECK ((`duplicate_count` <= `success_count`)),
  CONSTRAINT `ck_import_batches_6` CHECK ((`last_line_no` >= 0)),
  CONSTRAINT `ck_import_batches_7` CHECK ((`import_type` in (_utf8mb4'DATASET_CASES',_utf8mb4'OFFLINE_RUNS'))),
  CONSTRAINT `ck_import_batches_8` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'PARTIAL_FAILED',_utf8mb4'FAILED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='导入文件与进度，恢复时锁批次行';

CREATE TABLE `import_errors` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `line_no` bigint NOT NULL,
  `error_code` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_bin NOT NULL,
  `record_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_errors_batch_id_line_no` (`batch_id`,`line_no`),
  CONSTRAINT `fk_import_errors_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_import_errors_1` CHECK ((`line_no` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='脱敏错误定位，不存原始敏感行';

CREATE TABLE `import_records` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `line_no` bigint NOT NULL,
  `record_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL,
  `dataset_case_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_records_batch_id_line_no` (`batch_id`,`line_no`),
  KEY `idx_import_records_dataset_case_id` (`dataset_case_id`),
  KEY `idx_import_records_run_id` (`run_id`),
  CONSTRAINT `fk_import_records_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_records_dataset_case_id` FOREIGN KEY (`dataset_case_id`) REFERENCES `dataset_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_records_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_import_records_1` CHECK ((`line_no` >= 1)),
  CONSTRAINT `ck_import_records_2` CHECK ((`status` in (_utf8mb4'IMPORTED',_utf8mb4'REUSED',_utf8mb4'REJECTED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每行导入回执，确保重放不重复计数';

CREATE TABLE `inbox_events` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `consumer_name` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `event_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `processed_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbox_events_consumer_name_event_id` (`consumer_name`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='通知去重记录，TASK-011启用';

CREATE TABLE `outbox_events` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `aggregate_type` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `aggregate_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_type` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `event_version` int NOT NULL DEFAULT '1',
  `payload_json` json NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `retry_count` int NOT NULL DEFAULT '0',
  `next_retry_at` datetime(3) DEFAULT NULL,
  `publisher_id` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `claim_token_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_events_status_next_retry_at_created_at_id` (`status`,`next_retry_at`,`created_at`,`id`),
  CONSTRAINT `ck_outbox_events_1` CHECK ((`event_version` >= 1)),
  CONSTRAINT `ck_outbox_events_2` CHECK ((`retry_count` >= 0)),
  CONSTRAINT `ck_outbox_events_3` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PUBLISHED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='事务消息，TASK-002建表，TASK-011实现发布';

CREATE TABLE `run_attempts` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `attempt_no` int NOT NULL,
  `worker_id` varchar(128) COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'RUNNING',
  `lease_token_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `lease_until` datetime(3) NOT NULL,
  `deadline_at` datetime(3) NOT NULL,
  `completion_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `output_json` json DEFAULT NULL,
  `output_artifact_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `usage_json` json DEFAULT NULL,
  `started_at` datetime(3) NOT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `error_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  `error_message` varchar(1024) COLLATE utf8mb4_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_attempts_run_id_attempt_no` (`run_id`,`attempt_no`),
  UNIQUE KEY `uk_run_attempts_run_id_id` (`run_id`,`id`),
  KEY `idx_run_attempts_status_lease_until` (`status`,`lease_until`),
  KEY `idx_run_attempts_output_artifact_id` (`output_artifact_id`),
  CONSTRAINT `fk_run_attempts_output_artifact_id` FOREIGN KEY (`output_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_run_attempts_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_run_attempts_1` CHECK ((`attempt_no` >= 1)),
  CONSTRAINT `ck_run_attempts_2` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'TIMED_OUT',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每次执行尝试；失败重试保留历史';

CREATE TABLE `trace_events` (
  `id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` char(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_attempt_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `source_event_id` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
  `event_hash` char(64) COLLATE utf8mb4_bin NOT NULL,
  `trace_id` char(32) COLLATE utf8mb4_bin DEFAULT NULL,
  `span_id` char(16) COLLATE utf8mb4_bin DEFAULT NULL,
  `parent_span_id` char(16) COLLATE utf8mb4_bin DEFAULT NULL,
  `sequence_no` bigint DEFAULT NULL,
  `event_type` varchar(64) COLLATE utf8mb4_bin NOT NULL,
  `occurred_at` datetime(3) NOT NULL,
  `payload_json` json NOT NULL,
  `artifact_id` char(26) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_events_event_id` (`event_id`),
  KEY `idx_trace_events_run_id_occurred_at_id` (`run_id`,`occurred_at`,`id`),
  KEY `idx_trace_events_trace_id` (`trace_id`),
  KEY `idx_trace_events_run_id_event_type` (`run_id`,`event_type`),
  KEY `idx_trace_events_run_attempt_id_sequence_no_id` (`run_attempt_id`,`sequence_no`,`id`),
  KEY `idx_trace_events_run_id_run_attempt_id` (`run_id`,`run_attempt_id`),
  KEY `idx_trace_events_artifact_id` (`artifact_id`),
  CONSTRAINT `fk_trace_events_artifact_id` FOREIGN KEY (`artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_trace_events_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_trace_events_run_id_run_attempt_id` FOREIGN KEY (`run_id`, `run_attempt_id`) REFERENCES `run_attempts` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_trace_events_1` CHECK (((`sequence_no` is null) or (`sequence_no` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='规范化、追加式轨迹事件';
