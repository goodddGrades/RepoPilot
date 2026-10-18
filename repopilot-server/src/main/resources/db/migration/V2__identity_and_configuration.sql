-- V2 身份、数据集与评测方案配置
-- 由 tools/gen_ddl_from_dict.py 依据 docs/contracts/database-schema.json 生成，请改字典后重新生成，不要手改本文件。
-- 外键策略：父表已建则内联；循环引用或父表后建的组合统一放到 V7。

-- agents：Agent身份，不持有运行密钥
CREATE TABLE `agents` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` TEXT,
  `agent_type` VARCHAR(32) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agents_code` (`code`),
  CONSTRAINT `ck_agents_1` CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Agent身份，不持有运行密钥';

-- agent_versions：发布后不可变的执行配置
CREATE TABLE `agent_versions` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `agent_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` INT NOT NULL,
  `runner_type` VARCHAR(32) NOT NULL,
  `config_json` JSON NOT NULL,
  `config_hash` CHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_versions_agent_id_version` (`agent_id`, `version`),
  CONSTRAINT `ck_agent_versions_1` CHECK (version >= 1),
  CONSTRAINT `ck_agent_versions_2` CHECK (runner_type IN ('MOCK', 'CODEX', 'CUSTOM')),
  CONSTRAINT `ck_agent_versions_3` CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  CONSTRAINT `fk_agent_versions_agent_id` FOREIGN KEY (`agent_id`) REFERENCES `agents` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布后不可变的执行配置';

-- datasets：数据集身份
CREATE TABLE `datasets` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` TEXT,
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_datasets_code` (`code`),
  CONSTRAINT `ck_datasets_1` CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='数据集身份';

-- dataset_versions：发布后固定样本集合
CREATE TABLE `dataset_versions` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dataset_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` INT NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  `description` TEXT,
  `content_hash` CHAR(64),
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_versions_dataset_id_version` (`dataset_id`, `version`),
  CONSTRAINT `ck_dataset_versions_1` CHECK (version >= 1),
  CONSTRAINT `ck_dataset_versions_2` CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  CONSTRAINT `fk_dataset_versions_dataset_id` FOREIGN KEY (`dataset_id`) REFERENCES `datasets` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布后固定样本集合';

-- dataset_cases：版本内样本身份
CREATE TABLE `dataset_cases` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dataset_version_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `case_key` VARCHAR(128) NOT NULL,
  `task_type` VARCHAR(32) NOT NULL,
  `input_json` JSON NOT NULL,
  `expected_json` JSON,
  `metadata_json` JSON,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_cases_dataset_version_id_case_key` (`dataset_version_id`, `case_key`),
  CONSTRAINT `fk_dataset_cases_dataset_version_id` FOREIGN KEY (`dataset_version_id`) REFERENCES `dataset_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='版本内样本身份';

-- evaluator_definitions：算子实现与输入输出契约版本
CREATE TABLE `evaluator_definitions` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluator_key` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL,
  `display_name` VARCHAR(128) NOT NULL,
  `description` TEXT,
  `evaluator_type` VARCHAR(32) NOT NULL,
  `implementation_ref` VARCHAR(256) NOT NULL,
  `input_schema_json` JSON NOT NULL,
  `output_schema_json` JSON NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluator_definitions_evaluator_key_version` (`evaluator_key`, `version`),
  CONSTRAINT `ck_evaluator_definitions_1` CHECK (version >= 1),
  CONSTRAINT `ck_evaluator_definitions_2` CHECK (evaluator_type IN ('RULE', 'TRACE', 'COST', 'TEST', 'LLM_JUDGE')),
  CONSTRAINT `ck_evaluator_definitions_3` CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='算子实现与输入输出契约版本';

-- evaluation_plans：评测方案身份
CREATE TABLE `evaluation_plans` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `code` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` TEXT,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plans_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='评测方案身份';

-- evaluation_plan_versions：方案配置快照
CREATE TABLE `evaluation_plan_versions` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `plan_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `version` INT NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  `config_json` JSON NOT NULL,
  `content_hash` CHAR(64),
  `published_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plan_versions_plan_id_version` (`plan_id`, `version`),
  CONSTRAINT `ck_evaluation_plan_versions_1` CHECK (version >= 1),
  CONSTRAINT `ck_evaluation_plan_versions_2` CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  CONSTRAINT `fk_evaluation_plan_versions_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `evaluation_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='方案配置快照';

-- evaluation_plan_items：顺序执行的固定算子配置
CREATE TABLE `evaluation_plan_items` (
  `id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `plan_version_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evaluator_definition_id` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `execution_order` INT NOT NULL,
  `required` BOOLEAN NOT NULL DEFAULT TRUE,
  `config_json` JSON NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_id` (`plan_version_id`, `id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_evaluator_definition_id` (`plan_version_id`, `evaluator_definition_id`),
  UNIQUE KEY `uk_evaluation_plan_items_plan_version_id_execution_order` (`plan_version_id`, `execution_order`),
  KEY `idx_evaluation_plan_items_evaluator_definition_id` (`evaluator_definition_id`),
  CONSTRAINT `ck_evaluation_plan_items_1` CHECK (execution_order >= 1),
  CONSTRAINT `fk_evaluation_plan_items_plan_version_id` FOREIGN KEY (`plan_version_id`) REFERENCES `evaluation_plan_versions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_evaluation_plan_items_evaluator_definition_id` FOREIGN KEY (`evaluator_definition_id`) REFERENCES `evaluator_definitions` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='顺序执行的固定算子配置';
