-- V4 评测批次、批次 Run 登记、Job 与结果
-- 由 tools/gen_ddl_from_dict.py 依据 docs/contracts/database-schema.json 生成，请改字典后重新生成，不要手改本文件。
-- 外键策略：父表已建则内联；循环引用或父表后建的组合统一放到 V7。

-- evaluation_batches：一次对待评测Run集合按固定方案版本的批量评测；Run集合由evaluation_batch_runs固定，重评创建新批次；每实验最多一个INITIAL批次由生成列唯一键强制
CREATE TABLE `evaluation_batches` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_version_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_no` INT NOT NULL,
  `trigger_type` VARCHAR(32) NOT NULL,
  `parent_batch_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `initial_experiment_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (IF(trigger_type = 'INITIAL', experiment_id, NULL)) STORED,
  `selection_json` JSON NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `expected_job_count` BIGINT NOT NULL DEFAULT 0,
  `created_by` VARCHAR(128) NOT NULL,
  `started_at` DATETIME(3),
  `finished_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_batches_experiment_id_batch_no` (`experiment_id`, `batch_no`),
  UNIQUE KEY `uk_evaluation_batches_id_evaluation_plan_version_id` (`id`, `evaluation_plan_version_id`),
  UNIQUE KEY `uk_evaluation_batches_id_experiment_id` (`id`, `experiment_id`),
  UNIQUE KEY `uk_evaluation_batches_initial_experiment_id` (`initial_experiment_id`),
  KEY `idx_evaluation_batches_experiment_id_status_created_at` (`experiment_id`, `status`, `created_at`),
  KEY `idx_evaluation_batches_status_created_at_id` (`status`, `created_at`, `id`),
  KEY `idx_evaluation_batches_evaluation_plan_version_id` (`evaluation_plan_version_id`),
  KEY `idx_evaluation_batches_parent_batch_id_experiment_id` (`parent_batch_id`, `experiment_id`),
  CONSTRAINT `ck_evaluation_batches_1` CHECK (batch_no >= 1),
  CONSTRAINT `ck_evaluation_batches_2` CHECK (expected_job_count >= 0),
  CONSTRAINT `ck_evaluation_batches_3` CHECK (trigger_type IN ('INITIAL', 'REEVALUATION', 'MANUAL')),
  CONSTRAINT `ck_evaluation_batches_4` CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')),
  CONSTRAINT `ck_evaluation_batches_5` CHECK (trigger_type <> 'INITIAL' OR parent_batch_id IS NULL),
  CONSTRAINT `ck_evaluation_batches_6` CHECK (parent_batch_id IS NULL OR parent_batch_id <> id),
  CONSTRAINT `fk_evaluation_batches_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_batches_evaluation_plan_version_id` FOREIGN KEY (`evaluation_plan_version_id`) REFERENCES `evaluation_plan_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='一次对待评测Run集合按固定方案版本的批量评测；Run集合由evaluation_batch_runs固定，重评创建新批次；每实验最多一个INITIAL批次由生成列唯一键强制';

-- evaluation_batch_runs：批次固定登记的Run集合；主键即(batch_id, run_id)，无独立生命周期
CREATE TABLE `evaluation_batch_runs` (
  `batch_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`batch_id`, `run_id`),
  KEY `idx_evaluation_batch_runs_batch_id_experiment_id` (`batch_id`, `experiment_id`),
  KEY `idx_evaluation_batch_runs_run_id_experiment_id` (`run_id`, `experiment_id`),
  CONSTRAINT `fk_evaluation_batch_runs_batch_id_experiment_id` FOREIGN KEY (`batch_id`, `experiment_id`) REFERENCES `evaluation_batches` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_batch_runs_run_id_experiment_id` FOREIGN KEY (`run_id`, `experiment_id`) REFERENCES `evaluation_runs` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='批次固定登记的Run集合；主键即(batch_id, run_id)，无独立生命周期';

-- evaluation_jobs：批次内Run×方案项的逻辑评测任务；Run必须已登记在evaluation_batch_runs
CREATE TABLE `evaluation_jobs` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_version_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_plan_item_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `active_attempt_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `next_retry_at` DATETIME(3),
  `input_hash` CHAR(64) NOT NULL,
  `started_at` DATETIME(3),
  `finished_at` DATETIME(3),
  `error_code` VARCHAR(64),
  `error_message` VARCHAR(1024),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_jobs_batch_id_run_id_evaluation_plan_item_id` (`batch_id`, `run_id`, `evaluation_plan_item_id`),
  UNIQUE KEY `uk_evaluation_jobs_run_id_id` (`run_id`, `id`),
  KEY `idx_evaluation_jobs_status_next_retry_at_created_at_id` (`status`, `next_retry_at`, `created_at`, `id`),
  KEY `idx_evaluation_jobs_batch_id_evaluation_plan_version_id` (`batch_id`, `evaluation_plan_version_id`),
  KEY `idx_evaluation_jobs_evaluation_plan_version_id_evaluation_ca06b0` (`evaluation_plan_version_id`, `evaluation_plan_item_id`),
  KEY `idx_evaluation_jobs_id_active_attempt_id` (`id`, `active_attempt_id`),
  CONSTRAINT `ck_evaluation_jobs_1` CHECK (attempt_count >= 0),
  CONSTRAINT `ck_evaluation_jobs_2` CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')),
  CONSTRAINT `fk_evaluation_jobs_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `evaluation_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_batch_id_evaluation_plan_version_id` FOREIGN KEY (`batch_id`, `evaluation_plan_version_id`) REFERENCES `evaluation_batches` (`id`, `evaluation_plan_version_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_evaluation_plan_version_id_evaluation_dd5897` FOREIGN KEY (`evaluation_plan_version_id`, `evaluation_plan_item_id`) REFERENCES `evaluation_plan_items` (`plan_version_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_jobs_batch_id_run_id` FOREIGN KEY (`batch_id`, `run_id`) REFERENCES `evaluation_batch_runs` (`batch_id`, `run_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='批次内Run×方案项的逻辑评测任务；Run必须已登记在evaluation_batch_runs';

-- evaluation_job_attempts：算子重试历史与租约
CREATE TABLE `evaluation_job_attempts` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `attempt_no` INT NOT NULL,
  `worker_id` VARCHAR(128) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  `lease_token_hash` CHAR(64) NOT NULL,
  `lease_until` DATETIME(3) NOT NULL,
  `deadline_at` DATETIME(3) NOT NULL,
  `completion_hash` CHAR(64),
  `result_json` JSON,
  `usage_json` JSON,
  `error_code` VARCHAR(64),
  `error_message` VARCHAR(1024),
  `started_at` DATETIME(3) NOT NULL,
  `finished_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_job_attempts_job_id_attempt_no` (`job_id`, `attempt_no`),
  UNIQUE KEY `uk_evaluation_job_attempts_job_id_id` (`job_id`, `id`),
  KEY `idx_evaluation_job_attempts_status_lease_until` (`status`, `lease_until`),
  CONSTRAINT `ck_evaluation_job_attempts_1` CHECK (attempt_no >= 1),
  CONSTRAINT `ck_evaluation_job_attempts_2` CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
  CONSTRAINT `fk_evaluation_job_attempts_job_id` FOREIGN KEY (`job_id`) REFERENCES `evaluation_jobs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='算子重试历史与租约';

-- evaluation_results：每个Job唯一最终自动结论
CREATE TABLE `evaluation_results` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_attempt_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `verdict` VARCHAR(32) NOT NULL,
  `score` DECIMAL(6,5),
  `reason` TEXT,
  `evidence_json` JSON,
  `result_json` JSON NOT NULL,
  `generated_by` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_results_job_id` (`job_id`),
  KEY `idx_evaluation_results_job_id_job_attempt_id` (`job_id`, `job_attempt_id`),
  CONSTRAINT `ck_evaluation_results_1` CHECK (score IS NULL OR (score >= 0 AND score <= 1)),
  CONSTRAINT `ck_evaluation_results_2` CHECK (verdict IN ('PASS', 'FAIL', 'INDETERMINATE', 'NOT_APPLICABLE')),
  CONSTRAINT `ck_evaluation_results_3` CHECK (generated_by IN ('RULE', 'TEST', 'LLM', 'MOCK')),
  CONSTRAINT `fk_evaluation_results_job_id` FOREIGN KEY (`job_id`) REFERENCES `evaluation_jobs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_results_job_id_job_attempt_id` FOREIGN KEY (`job_id`, `job_attempt_id`) REFERENCES `evaluation_job_attempts` (`job_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每个Job唯一最终自动结论';
