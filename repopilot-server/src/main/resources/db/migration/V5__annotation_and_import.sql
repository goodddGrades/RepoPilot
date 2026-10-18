-- V5 人工标注与导入记账
-- 由 tools/gen_ddl_from_dict.py 依据 docs/contracts/database-schema.json 生成，请改字典后重新生成，不要手改本文件。
-- 外键策略：父表已建则内联；循环引用或父表后建的组合统一放到 V7。

-- annotation_tasks：人工复核当前状态
CREATE TABLE `annotation_tasks` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `run_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluation_job_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `assignee_id` VARCHAR(128),
  `claimed_at` DATETIME(3),
  `submitted_at` DATETIME(3),
  `reviewed_at` DATETIME(3),
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_annotation_tasks_status_created_at_id` (`status`, `created_at`, `id`),
  KEY `idx_annotation_tasks_run_id_evaluation_job_id` (`run_id`, `evaluation_job_id`),
  CONSTRAINT `ck_annotation_tasks_1` CHECK (version >= 0),
  CONSTRAINT `ck_annotation_tasks_2` CHECK (status IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'REVIEWED', 'RETURNED', 'CANCELLED')),
  CONSTRAINT `fk_annotation_tasks_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_annotation_tasks_run_id_evaluation_job_id` FOREIGN KEY (`run_id`, `evaluation_job_id`) REFERENCES `evaluation_jobs` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='人工复核当前状态';

-- annotation_records：追加式人工操作历史
CREATE TABLE `annotation_records` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `annotation_task_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator_id` VARCHAR(128) NOT NULL,
  `action` VARCHAR(32) NOT NULL,
  `verdict` VARCHAR(32),
  `failure_category` VARCHAR(64),
  `reason` TEXT,
  `evidence_json` JSON,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_annotation_records_annotation_task_id_created_at_id` (`annotation_task_id`, `created_at`, `id`),
  CONSTRAINT `ck_annotation_records_1` CHECK (action IN ('CLAIM', 'SUBMIT', 'REVIEW', 'RETURN', 'CANCEL')),
  CONSTRAINT `ck_annotation_records_2` CHECK (verdict IN ('PASS', 'FAIL', 'INDETERMINATE', 'NOT_APPLICABLE')),
  CONSTRAINT `fk_annotation_records_annotation_task_id` FOREIGN KEY (`annotation_task_id`) REFERENCES `annotation_tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='追加式人工操作历史';

-- import_batches：导入文件与进度，恢复时锁批次行
CREATE TABLE `import_batches` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `import_type` VARCHAR(32) NOT NULL,
  `dataset_version_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `experiment_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `file_artifact_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `total_count` BIGINT NOT NULL DEFAULT 0,
  `success_count` BIGINT NOT NULL DEFAULT 0,
  `failed_count` BIGINT NOT NULL DEFAULT 0,
  `duplicate_count` BIGINT NOT NULL DEFAULT 0,
  `last_line_no` BIGINT NOT NULL DEFAULT 0,
  `started_at` DATETIME(3),
  `finished_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_import_batches_experiment_id_status` (`experiment_id`, `status`),
  KEY `idx_import_batches_status_created_at_id` (`status`, `created_at`, `id`),
  KEY `idx_import_batches_dataset_version_id` (`dataset_version_id`),
  KEY `idx_import_batches_file_artifact_id` (`file_artifact_id`),
  CONSTRAINT `ck_import_batches_1` CHECK (total_count >= 0),
  CONSTRAINT `ck_import_batches_2` CHECK (success_count >= 0),
  CONSTRAINT `ck_import_batches_3` CHECK (failed_count >= 0),
  CONSTRAINT `ck_import_batches_4` CHECK (duplicate_count >= 0),
  CONSTRAINT `ck_import_batches_5` CHECK (duplicate_count <= success_count),
  CONSTRAINT `ck_import_batches_6` CHECK (last_line_no >= 0),
  CONSTRAINT `ck_import_batches_7` CHECK (import_type IN ('DATASET_CASES', 'OFFLINE_RUNS')),
  CONSTRAINT `ck_import_batches_8` CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')),
  CONSTRAINT `fk_import_batches_dataset_version_id` FOREIGN KEY (`dataset_version_id`) REFERENCES `dataset_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_batches_experiment_id` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_batches_file_artifact_id` FOREIGN KEY (`file_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='导入文件与进度，恢复时锁批次行';

-- import_records：每行导入回执，确保重放不重复计数
CREATE TABLE `import_records` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `line_no` BIGINT NOT NULL,
  `record_hash` CHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `dataset_case_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `run_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_records_batch_id_line_no` (`batch_id`, `line_no`),
  KEY `idx_import_records_dataset_case_id` (`dataset_case_id`),
  KEY `idx_import_records_run_id` (`run_id`),
  CONSTRAINT `ck_import_records_1` CHECK (line_no >= 1),
  CONSTRAINT `ck_import_records_2` CHECK (status IN ('IMPORTED', 'REUSED', 'REJECTED')),
  CONSTRAINT `fk_import_records_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_records_dataset_case_id` FOREIGN KEY (`dataset_case_id`) REFERENCES `dataset_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_import_records_run_id` FOREIGN KEY (`run_id`) REFERENCES `evaluation_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每行导入回执，确保重放不重复计数';

-- import_errors：脱敏错误定位，不存原始敏感行
CREATE TABLE `import_errors` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `batch_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `line_no` BIGINT NOT NULL,
  `error_code` VARCHAR(64) NOT NULL,
  `error_message` VARCHAR(1024) NOT NULL,
  `record_hash` CHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_errors_batch_id_line_no` (`batch_id`, `line_no`),
  CONSTRAINT `ck_import_errors_1` CHECK (line_no >= 1),
  CONSTRAINT `fk_import_errors_batch_id` FOREIGN KEY (`batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='脱敏错误定位，不存原始敏感行';
