package com.repopilot.it;

import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库约束验收：每一条"应该被拒绝"的场景都真的被 MySQL 拒绝。
 *
 * <p>断言的是 MySQL 错误码而不是异常类型：
 * <ul>
 *   <li>1062 duplicate entry —— 唯一键/主键；</li>
 *   <li>1452 foreign key constraint fails —— 外键不存在或跨归属；</li>
 *   <li>1451 cannot delete or update a parent row —— RESTRICT；</li>
 *   <li>3819 check constraint violated —— CHECK。</li>
 * </ul>
 * 只断言"抛异常"是不够的：语法错误也会抛异常，但那是测试写错而不是约束生效。
 */
@Transactional
class ConstraintIT extends AbstractDatabaseIT {

    private static final int DUPLICATE_ENTRY = 1062;
    private static final int FOREIGN_KEY_MISSING = 1452;
    private static final int DELETE_RESTRICTED = 1451;
    private static final int CHECK_VIOLATED = 3819;

    @Autowired
    private JdbcTemplate jdbc;

    private Fixtures fixtures;
    private Fixtures.Graph graph;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        graph = fixtures.seed();
    }

    @Test
    void secondInitialBatchInSameExperimentShouldBeRejected() {
        assertSqlState(DUPLICATE_ENTRY, () ->
                fixtures.batch(graph.experimentId(), graph.planVersionId(), 2, "INITIAL", null));
    }

    @Test
    void reevaluationBatchInSameExperimentShouldBeAllowed() {
        String second = fixtures.secondPlanVersion(graph.planId(), 2);
        String reassessment = fixtures.batch(graph.experimentId(), second, 2, "REEVALUATION",
                graph.batchId());
        String third = fixtures.batch(graph.experimentId(), second, 3, "MANUAL", null);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?",
                Integer.class, graph.experimentId());
        assertThat(count).isEqualTo(3);
        assertThat(reassessment).isNotEqualTo(third);
    }

    @Test
    void initialBatchMustNotHaveParent() {
        assertSqlState(CHECK_VIOLATED, () ->
                fixtures.batch(graph.experimentId(), graph.planVersionId(), 9, "INITIAL", graph.batchId()));
    }

    @Test
    void batchMustNotBeItsOwnParent() {
        String id = Ulid.newId();
        Timestamp now = Timestamp.from(Instant.now());
        assertSqlState(CHECK_VIOLATED, () -> jdbc.update(
                "INSERT INTO evaluation_batches (id, experiment_id, evaluation_plan_version_id, batch_no,"
                        + " trigger_type, parent_batch_id, selection_json, status, expected_job_count,"
                        + " created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, graph.experimentId(), graph.planVersionId(), 7, "REEVALUATION", id,
                "{}", "PENDING", 0L, "it", now, now));
    }

    @Test
    void parentBatchFromAnotherExperimentShouldBeRejected() {
        String otherExperiment = otherExperiment();

        assertSqlState(FOREIGN_KEY_MISSING, () ->
                fixtures.batch(otherExperiment, graph.planVersionId(), 1, "REEVALUATION", graph.batchId()));
    }

    @Test
    void registeringSameRunTwiceInBatchShouldBeRejected() {
        assertSqlState(DUPLICATE_ENTRY, () ->
                fixtures.batchRun(graph.batchId(), graph.runId(), graph.experimentId()));
    }

    @Test
    void registeringRunOfAnotherExperimentShouldBeRejected() {
        String otherExperiment = otherExperiment();
        String otherTarget = fixtures.target(otherExperiment, "target-other", graph.agentVersionId(), 1);
        String otherRun = fixtures.run(otherExperiment, otherTarget, graph.caseId(), "ONLINE", 1);
        String otherBatch = fixtures.batch(otherExperiment, graph.planVersionId(), 1, "INITIAL", null);

        // 登记表 experiment_id 取被评测批次所属实验，两条复合外键就不可能同时满足
        assertSqlState(FOREIGN_KEY_MISSING, () ->
                fixtures.batchRun(otherBatch, graph.runId(), otherExperiment));
        assertSqlState(FOREIGN_KEY_MISSING, () -> fixtures.batchRun(otherBatch, otherRun, graph.experimentId()));
    }

    @Test
    void jobForRunNotRegisteredInBatchShouldBeRejected() {
        String otherTarget = fixtures.target(graph.experimentId(), "target-b", graph.agentVersionId(), 1);
        String unregisteredRun = fixtures.run(graph.experimentId(), otherTarget, graph.caseId(), "ONLINE", 1);

        assertSqlState(FOREIGN_KEY_MISSING, () ->
                fixtures.job(unregisteredRun, graph.batchId(), graph.planVersionId(), graph.planItemId()));
    }

    @Test
    void jobWithPlanItemFromOtherPlanVersionShouldBeRejected() {
        String secondPlanVersion = fixtures.secondPlanVersion(graph.planId(), 2);
        String evaluatorId = fixtures.evaluator("rule.other", 1);
        String foreignItem = fixtures.planItem(secondPlanVersion, evaluatorId, 1);

        // Job 声明方案版本 V1，却引用 V2 的算子项：复合外键 (plan_version_id, plan_item_id) 拒绝
        assertSqlState(FOREIGN_KEY_MISSING, () ->
                fixtures.job(graph.runId(), graph.batchId(), graph.planVersionId(), foreignItem));
    }

    @Test
    void sameRunInSecondBatchShouldBeAllowedButDuplicateJobInSameBatchIsNot() {
        String secondPlanVersion = fixtures.secondPlanVersion(graph.planId(), 2);
        String secondItem = fixtures.planItem(secondPlanVersion, graph.evaluatorId(), 1);
        String reassessment = fixtures.batch(graph.experimentId(), secondPlanVersion, 2,
                "REEVALUATION", graph.batchId());
        fixtures.batchRun(reassessment, graph.runId(), graph.experimentId());

        String original = fixtures.job(graph.runId(), graph.batchId(), graph.planVersionId(), graph.planItemId());
        String reJob = fixtures.job(graph.runId(), reassessment, secondPlanVersion, secondItem);
        assertThat(original).isNotEqualTo(reJob);

        assertSqlState(DUPLICATE_ENTRY, () ->
                fixtures.job(graph.runId(), reassessment, secondPlanVersion, secondItem));
    }

    @Test
    void duplicateAttemptNumberShouldBeRejected() {
        fixtures.runAttempt(graph.runId(), Ulid.newId(), 2);

        assertSqlState(DUPLICATE_ENTRY, () -> fixtures.runAttempt(graph.runId(), Ulid.newId(), 2));
    }

    @Test
    void secondFinalResultForSameJobShouldBeRejected() {
        String jobId = fixtures.job(graph.runId(), graph.batchId(), graph.planVersionId(), graph.planItemId());
        String attemptId = Ulid.newId();
        jdbc.update("INSERT INTO evaluation_job_attempts (id, job_id, attempt_no, worker_id, status,"
                        + " lease_token_hash, lease_until, deadline_at, started_at, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, jobId, 1, "worker-1", "COMPLETED", "0".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(120)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        insertResult(jobId, attemptId, "0.5");

        assertSqlState(DUPLICATE_ENTRY, () -> insertResult(jobId, attemptId, "0.9"));
    }

    @Test
    void scoreOutOfRangeShouldBeRejected() {
        String jobId = fixtures.job(graph.runId(), graph.batchId(), graph.planVersionId(), graph.planItemId());
        String attemptId = Ulid.newId();
        jdbc.update("INSERT INTO evaluation_job_attempts (id, job_id, attempt_no, worker_id, status,"
                        + " lease_token_hash, lease_until, deadline_at, started_at, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, jobId, 1, "worker-1", "COMPLETED", "0".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(60)),
                Timestamp.from(Instant.now().plusSeconds(120)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        assertSqlState(CHECK_VIOLATED, () -> insertResult(jobId, attemptId, "1.5"));
    }

    @Test
    void unknownStatusShouldBeRejected() {
        assertSqlState(CHECK_VIOLATED, () -> jdbc.update(
                "UPDATE evaluation_batches SET status = 'DONE' WHERE id = ?", graph.batchId()));
    }

    @Test
    void deletingReferencedRunShouldBeRestricted() {
        assertSqlState(DELETE_RESTRICTED, () ->
                jdbc.update("DELETE FROM evaluation_runs WHERE id = ?", graph.runId()));
    }

    @Test
    void duplicateTraceEventIdShouldBeRejected() {
        String eventId = Ulid.newId();
        Timestamp now = Timestamp.from(Instant.now());
        String insert = "INSERT INTO trace_events (id, event_id, run_id, event_hash, event_type,"
                + " occurred_at, payload_json, created_at) VALUES (?,?,?,?,?,?,?,?)";
        jdbc.update(insert, Ulid.newId(), eventId, graph.runId(), "0".repeat(64), "MESSAGE", now, "{}", now);

        assertSqlState(DUPLICATE_ENTRY, () ->
                jdbc.update(insert, Ulid.newId(), eventId, graph.runId(), "0".repeat(64), "MESSAGE", now, "{}", now));
    }

    private void insertResult(String jobId, String attemptId, String score) {
        jdbc.update("INSERT INTO evaluation_results (id, job_id, job_attempt_id, verdict, score,"
                        + " result_json, generated_by, created_at) VALUES (?,?,?,?,?,?,?,?)",
                Ulid.newId(), jobId, attemptId, "PASS", score, "{}", "RULE",
                Timestamp.from(Instant.now()));
    }

    private String otherExperiment() {
        String datasetVersionId = graph.datasetVersionId();
        return fixtures.experiment("exp-other-" + Ulid.newId().substring(20), "ONLINE",
                datasetVersionId, graph.planVersionId());
    }

    private void assertSqlState(int expectedCode, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlErrorCode(ex)).isEqualTo(expectedCode));
    }

    private int sqlErrorCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getErrorCode();
            }
            current = current.getCause();
        }
        throw new AssertionError("异常链中没有 SQLException", throwable);
    }
}
