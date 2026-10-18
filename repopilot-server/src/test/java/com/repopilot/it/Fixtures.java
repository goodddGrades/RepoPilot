package com.repopilot.it;

import com.repopilot.common.id.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 集成测试夹具：用原生 SQL 造出最小可用的实验图。
 *
 * <p>刻意不使用 Mapper：如果夹具本身依赖被测的持久化代码，Mapper 写错时夹具也会一起错，
 * 测试就失去意义。原生 SQL 只依赖真实表结构，能独立地证明约束行为。
 */
public final class Fixtures {

    /** 一次实验图涉及的主键，供各个断言按需引用。 */
    public record Graph(String agentId, String agentVersionId, String datasetId, String datasetVersionId,
                        String caseId, String evaluatorId, String planId, String planVersionId,
                        String planItemId, String experimentId, String targetId, String runId,
                        String attemptId, String batchId) {
    }

    private final JdbcTemplate jdbc;
    private final Timestamp now = Timestamp.from(Instant.now());

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void insert(String sql, Object... args) {
        jdbc.update(sql, args);
    }

    public String agent(String code) {
        String id = Ulid.newId();
        insert("INSERT INTO agents (id, code, name, agent_type, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?)", id, code, "测试 Agent", "MOCK", "ACTIVE", now, now);
        return id;
    }

    public String agentVersion(String agentId, int version) {
        String id = Ulid.newId();
        insert("INSERT INTO agent_versions (id, agent_id, version, runner_type, config_json, config_hash,"
                        + " status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, agentId, version, "MOCK", "{}", "0".repeat(64), "PUBLISHED", now, now);
        return id;
    }

    public String dataset(String code) {
        String id = Ulid.newId();
        insert("INSERT INTO datasets (id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                id, code, "测试数据集", "ACTIVE", now, now);
        return id;
    }

    public String datasetVersion(String datasetId, int version, String status) {
        String id = Ulid.newId();
        insert("INSERT INTO dataset_versions (id, dataset_id, version, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?)", id, datasetId, version, status, now, now);
        return id;
    }

    public String datasetCase(String datasetVersionId, String caseKey) {
        String id = Ulid.newId();
        insert("INSERT INTO dataset_cases (id, dataset_version_id, case_key, task_type, input_json,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                id, datasetVersionId, caseKey, "CODING", "{\"prompt\":\"修复空指针\"}", now, now);
        return id;
    }

    public String evaluator(String key, int version) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluator_definitions (id, evaluator_key, version, display_name, evaluator_type,"
                        + " implementation_ref, input_schema_json, output_schema_json, status,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, key, version, "测试算子", "RULE", "rule://noop", "{}", "{}", "PUBLISHED", now, now);
        return id;
    }

    public String plan(String code) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_plans (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
                id, code, "测试方案", now, now);
        return id;
    }

    public String planVersion(String planId, int version) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_plan_versions (id, plan_id, version, status, config_json,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                id, planId, version, "PUBLISHED", "{}", now, now);
        return id;
    }

    public String planItem(String planVersionId, String evaluatorId, int order) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_plan_items (id, plan_version_id, evaluator_definition_id,"
                        + " execution_order, required, config_json, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                id, planVersionId, evaluatorId, order, true, "{}", now, now);
        return id;
    }

    public String experiment(String name, String mode, String datasetVersionId, String planVersionId) {
        String id = Ulid.newId();
        insert("INSERT INTO experiments (id, name, mode, dataset_version_id, evaluation_plan_version_id,"
                        + " status, config_json, expected_run_count, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, name, mode, datasetVersionId, planVersionId, "RUNNING", "{}", 1L, now, now);
        return id;
    }

    public String target(String experimentId, String targetKey, String agentVersionId, int repeatCount) {
        String id = Ulid.newId();
        insert("INSERT INTO experiment_targets (id, experiment_id, target_key, display_name,"
                        + " agent_version_id, repeat_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                id, experimentId, targetKey, "测试目标", agentVersionId, repeatCount, now, now);
        return id;
    }

    public String run(String experimentId, String targetId, String caseId, String mode, int repeatIndex) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_runs (id, experiment_id, experiment_target_id, dataset_case_id,"
                        + " repeat_index, mode, status, task_outcome, observation_metadata_json,"
                        + " attempt_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, experimentId, targetId, caseId, repeatIndex, mode, "PENDING", "UNKNOWN", "{}", 0, now, now);
        return id;
    }

    public String runAttempt(String runId, String attemptId, int attemptNo) {
        insert("INSERT INTO run_attempts (id, run_id, attempt_no, worker_id, status, lease_token_hash,"
                        + " lease_until, deadline_at, started_at, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, runId, attemptNo, "worker-1", "RUNNING", "0".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(120)), now, now, now);
        return attemptId;
    }

    public String batch(String experimentId, String planVersionId, int batchNo, String triggerType,
                        String parentBatchId) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_batches (id, experiment_id, evaluation_plan_version_id, batch_no,"
                        + " trigger_type, parent_batch_id, selection_json, status, expected_job_count,"
                        + " created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, experimentId, planVersionId, batchNo, triggerType, parentBatchId, "{\"runIds\":[]}",
                "PENDING", 0L, "it", now, now);
        return id;
    }

    public void batchRun(String batchId, String runId, String experimentId) {
        insert("INSERT INTO evaluation_batch_runs (batch_id, run_id, experiment_id, created_at)"
                + " VALUES (?,?,?,?)", batchId, runId, experimentId, now);
    }

    public String job(String runId, String batchId, String planVersionId, String planItemId) {
        String id = Ulid.newId();
        insert("INSERT INTO evaluation_jobs (id, run_id, batch_id, evaluation_plan_version_id,"
                        + " evaluation_plan_item_id, status, attempt_count, input_hash,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, runId, batchId, planVersionId, planItemId, "PENDING", 0, "0".repeat(64), now, now);
        return id;
    }

    /**
     * 最小在线实验图：一个 Agent/数据集/方案两版，一个 Run 及其 Attempt，一个 INITIAL 批次。
     *
     * <p>两版方案是刻意的：重评测试需要"同一 Run 用另一个方案版本再评一次"，
     * 而批次与 Job 的方案版本必须一致，因此第二版由调用方通过 {@link #secondPlanVersion} 创建。
     */
    public Graph seed() {
        String agentId = agent("agent-" + Ulid.newId().substring(20));
        String agentVersionId = agentVersion(agentId, 1);
        String datasetId = dataset("ds-" + Ulid.newId().substring(20));
        String datasetVersionId = datasetVersion(datasetId, 1, "PUBLISHED");
        String caseId = datasetCase(datasetVersionId, "case-1");
        String evaluatorId = evaluator("rule.noop", 1);
        String planId = plan("plan-" + Ulid.newId().substring(20));
        String planVersionId = planVersion(planId, 1);
        String planItemId = planItem(planVersionId, evaluatorId, 1);
        String experimentId = experiment("exp-" + Ulid.newId().substring(20), "ONLINE",
                datasetVersionId, planVersionId);
        String targetId = target(experimentId, "target-a", agentVersionId, 1);
        String runId = run(experimentId, targetId, caseId, "ONLINE", 1);
        String attemptId = runAttempt(runId, Ulid.newId(), 1);
        String batchId = batch(experimentId, planVersionId, 1, "INITIAL", null);
        batchRun(batchId, runId, experimentId);
        return new Graph(agentId, agentVersionId, datasetId, datasetVersionId, caseId, evaluatorId,
                planId, planVersionId, planItemId, experimentId, targetId, runId, attemptId, batchId);
    }

    /** 同方案的第二个已发布版本，用于"换算子版本重评必须新建批次"。 */
    public String secondPlanVersion(String planId, int version) {
        return planVersion(planId, version);
    }
}
