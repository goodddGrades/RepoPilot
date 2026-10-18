-- V7 延后外键：循环引用与父表后建的组合在此补齐
-- 这些外键的父表在本迁移之前已全部存在，但子表先于父表创建会形成环，
-- 因此在最后统一 ALTER TABLE 添加。全程不关闭 foreign_key_checks。

ALTER TABLE `evaluation_runs` ADD CONSTRAINT `fk_evaluation_runs_id_active_attempt_id` FOREIGN KEY (`id`, `active_attempt_id`) REFERENCES `run_attempts` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `evaluation_runs` ADD CONSTRAINT `fk_evaluation_runs_id_accepted_attempt_id` FOREIGN KEY (`id`, `accepted_attempt_id`) REFERENCES `run_attempts` (`run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `evaluation_runs` ADD CONSTRAINT `fk_evaluation_runs_output_artifact_id` FOREIGN KEY (`output_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `evaluation_runs` ADD CONSTRAINT `fk_evaluation_runs_import_batch_id` FOREIGN KEY (`import_batch_id`) REFERENCES `import_batches` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_attempts` ADD CONSTRAINT `fk_run_attempts_output_artifact_id` FOREIGN KEY (`output_artifact_id`) REFERENCES `artifacts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `evaluation_batches` ADD CONSTRAINT `fk_evaluation_batches_parent_batch_id_experiment_id` FOREIGN KEY (`parent_batch_id`, `experiment_id`) REFERENCES `evaluation_batches` (`id`, `experiment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `evaluation_jobs` ADD CONSTRAINT `fk_evaluation_jobs_id_active_attempt_id` FOREIGN KEY (`id`, `active_attempt_id`) REFERENCES `evaluation_job_attempts` (`job_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
