package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.modules.experiment.service.WorkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-004 的主链路验收：提交 → 领取 → 事件 → 封口 → Job → 重试/取消/过期。
 *
 * <p>全部走真实 HTTP 与真实 MySQL。没有 Mock 掉服务层，因此这些用例同时验证了
 * SQL（含 {@code FOR UPDATE OF r SKIP LOCKED}）、租约摘要、事件清单摘要与事务边界。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-004 在线执行主链路")
class ExecutionFlowIT extends AbstractExecutionIT {

    @Autowired
    private WorkService workService;

    @Test
    @DisplayName("2 Target × 3 Case × 2 重复 = 12 Run，只创建一个 INITIAL 批次")
    void startShouldGenerateTwelveRunsAndOneInitialBatch() {
        Scenario scenario = seedPublished(3);
        String experimentId = createExperiment(scenario, 2, 2);

        start(experimentId, "start-" + scenario.suffix());

        JsonNode experiment = data(get("/api/v1/experiments/" + experimentId));
        assertThat(experiment.path("status").asText()).isEqualTo("QUEUED");
        assertThat(experiment.path("expectedRunCount").asInt()).isEqualTo(12);

        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(12);
        // 提交事务后立即推进，避免演示等扫描
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ? AND status = 'QUEUED'",
                experimentId)).isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?", experimentId))
                .isEqualTo(1);
        assertThat(count("SELECT expected_job_count FROM evaluation_batches WHERE experiment_id = ?",
                experimentId)).isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batch_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(12);
        // 槽位身份：每个 Target × Case × 重复恰好一行
        assertThat(count("SELECT COUNT(*) FROM (SELECT experiment_target_id, dataset_case_id, repeat_index"
                + " FROM evaluation_runs WHERE experiment_id = ? GROUP BY 1,2,3 HAVING COUNT(*) = 1) t",
                experimentId)).isEqualTo(12);
    }

    @Test
    @DisplayName("重复 start 不新增 Run；换 key 再 start 返回 409")
    void duplicateStartShouldBeIdempotent() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 2);
        String key = "start-" + scenario.suffix();

        start(experimentId, key);
        start(experimentId, key);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(2);

        ResponseEntity<String> second = post("/api/v1/experiments/" + experimentId + "/start",
                "start-other-" + scenario.suffix(), "{}");
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(second)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("领取→事件→完成：Run 转 EVALUATING，幂等生成 PENDING Job，重复完成不重复建 Job")
    void completeShouldSealRunAndCreateOnePendingJob() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        List<JsonNode> tasks = claim("it-worker-1", 1);
        assertThat(tasks).hasSize(1);
        JsonNode task = tasks.get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        String token = task.path("leaseToken").asText();
        assertThat(task.path("input").path("datasetCase").path("caseKey").asText()).isNotBlank();
        assertThat(task.path("input").path("agentConfig").path("model").asText()).isEqualTo("mock-model");

        // 已领取的 Run 不会再发给别的 Worker
        assertThat(claim("it-worker-2", 1)).isEmpty();

        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());
        assertThat(eventIds).hasSize(5);
        assertThat(count("SELECT COUNT(*) FROM trace_events WHERE run_attempt_id = ?", attemptId))
                .isEqualTo(5);

        ObjectNode output = objectMapper.createObjectNode()
                .put("mock", true).put("answer", "42").put("caseKey", "case-1");
        ResponseEntity<String> completed = complete(attemptId, token, "SUCCESS", output, eventIds);
        assertThat(completed.getStatusCode().value())
                .as("完成应成功: %s", completed.getBody()).isEqualTo(200);

        JsonNode run = data(get("/api/v1/runs/" + runId));
        assertThat(run.path("run").path("status").asText()).isEqualTo("EVALUATING");
        assertThat(run.path("run").path("taskOutcome").asText()).isEqualTo("SUCCESS");
        assertThat(run.path("run").path("acceptedAttemptId").asText()).isEqualTo(attemptId);
        assertThat(run.path("traceEventCount").asInt()).isEqualTo(5);
        assertThat(run.path("output").path("mock").asBoolean()).isTrue();

        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ?", runId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ? AND status = 'PENDING'", runId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_batches WHERE id = ?", String.class,
                jdbc.queryForObject("SELECT batch_id FROM evaluation_jobs WHERE run_id = ?",
                        String.class, runId))).isEqualTo("RUNNING");

        // 同正文重放：原回执，不新增 Job
        ResponseEntity<String> replayed = complete(attemptId, token, "SUCCESS", output, eventIds);
        assertThat(replayed.getStatusCode().value()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ?", runId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isEqualTo(1);

        // 实验仍有 Run 在 EVALUATING（等算子），不能被提前判为完成
        assertThat(jdbc.queryForObject("SELECT status FROM experiments WHERE id = ?", String.class,
                experimentId)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("可重试失败：Run 保持 RUNNING，只追加 Attempt，不新增 Run")
    void retryableFailureShouldAppendAttemptOnly() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 2);
        start(experimentId, "start-" + scenario.suffix());

        List<JsonNode> tasks = claim("it-worker-1", 1);
        String attemptId = tasks.get(0).path("attemptId").asText();
        String runId = tasks.get(0).path("runId").asText();
        String token = tasks.get(0).path("leaseToken").asText();

        ResponseEntity<String> failed = fail(attemptId, token, "UPSTREAM_UNAVAILABLE", true);
        assertThat(failed.getStatusCode().value()).isEqualTo(200);
        assertThat(data(failed).path("retryScheduled").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE id = ?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT active_attempt_id FROM evaluation_runs WHERE id = ?",
                String.class, runId)).isNull();
        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(2);

        // 把退避时间拨到过去，模拟"等待结束"，由扫描推进
        jdbc.update("UPDATE evaluation_runs SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), runId);
        workService.maintain();

        List<JsonNode> again = claim("it-worker-1", 1);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).path("attemptNo").asInt()).isEqualTo(2);

        List<String> eventIds = appendEvents(again.get(0).path("attemptId").asText(),
                again.get(0).path("leaseToken").asText(), sampleEvents());
        ResponseEntity<String> completed = complete(again.get(0).path("attemptId").asText(),
                again.get(0).path("leaseToken").asText(), "SUCCESS",
                objectMapper.createObjectNode().put("answer", "ok"), eventIds);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);

        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isEqualTo(2);
        // 第一次尝试的轨迹保留，最终只接受第二次
        assertThat(jdbc.queryForObject("SELECT accepted_attempt_id FROM evaluation_runs WHERE id = ?",
                String.class, runId)).isEqualTo(again.get(0).path("attemptId").asText());
    }

    @Test
    @DisplayName("不可重试失败：Run 终态，实验无技术成功时收敛为 FAILED")
    void nonRetryableFailureShouldFailRunAndExperiment() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        ResponseEntity<String> failed = fail(task.path("attemptId").asText(),
                task.path("leaseToken").asText(), "INVALID_CONFIG", false);
        assertThat(failed.getStatusCode().value()).isEqualTo(200);
        assertThat(data(failed).path("retryScheduled").asBoolean()).isFalse();

        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE id = ?", String.class,
                task.path("runId").asText())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM experiments WHERE id = ?", String.class,
                experimentId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("租约过期：迟到的完成返回 409，回收后可用新 Attempt 完成")
    void expiredLeaseShouldRejectLateCompleteAndAllowNewAttempt() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        String token = task.path("leaseToken").asText();

        // 手工把租约推到过去：等价于 Worker 失联超过 60 秒，但不必真的等
        jdbc.update("UPDATE run_attempts SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(30)), attemptId);
        workService.maintain();

        assertThat(jdbc.queryForObject("SELECT status FROM run_attempts WHERE id = ?", String.class,
                attemptId)).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isEqualTo(1);

        // 失效 Attempt 的三条写入路径都必须被拒绝：续租、写事件、完成
        ResponseEntity<String> heartbeat = heartbeat(attemptId, token);
        assertThat(heartbeat.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(heartbeat)).isEqualTo("STALE_ATTEMPT");

        List<Map<String, Object>> lateEvents = sampleEvents();
        List<String> eventIds = eventIdsOf(lateEvents);
        ResponseEntity<String> rejectedEvents = appendEventsRaw(attemptId, token, lateEvents);
        assertThat(rejectedEvents.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(rejectedEvents)).isEqualTo("STALE_ATTEMPT");
        assertThat(count("SELECT COUNT(*) FROM trace_events WHERE run_attempt_id = ?", attemptId))
                .as("失效 Attempt 不能留下任何事件").isZero();

        ResponseEntity<String> late = complete(attemptId, token, "SUCCESS",
                objectMapper.createObjectNode().put("answer", "late"), eventIds);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("STALE_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE id = ?", String.class, runId))
                .isEqualTo("RUNNING");

        jdbc.update("UPDATE evaluation_runs SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), runId);
        workService.maintain();
        List<JsonNode> again = claim("it-worker-2", 1);
        assertThat(again).hasSize(1);
        String retryAttempt = again.get(0).path("attemptId").asText();
        assertThat(retryAttempt).isNotEqualTo(attemptId);
        List<String> secondEvents = appendEvents(retryAttempt, again.get(0).path("leaseToken").asText(),
                sampleEvents());
        assertThat(complete(retryAttempt, again.get(0).path("leaseToken").asText(), "SUCCESS",
                objectMapper.createObjectNode().put("answer", "ok"), secondEvents)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("取消实验：Run/Attempt 终态，迟到完成不复活")
    void cancelShouldStopRunsAndRejectLateComplete() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();

        ResponseEntity<String> cancelled = post("/api/v1/experiments/" + experimentId + "/cancel",
                "cancel-" + scenario.suffix(), "{\"reason\":\"测试取消\"}");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");

        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE experiment_id = ?",
                String.class, experimentId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM run_attempts WHERE id = ?", String.class,
                attemptId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_batches WHERE experiment_id = ?",
                String.class, experimentId)).isEqualTo("CANCELLED");

        // 实验取消后 Run 已封口：迟到的续租、事件、完成一律 409，Run 不会被复活
        assertThat(heartbeat(attemptId, token).getStatusCode().value()).isEqualTo(409);
        List<Map<String, Object>> lateEvents = sampleEvents();
        assertThat(appendEventsRaw(attemptId, token, lateEvents).getStatusCode().value())
                .isEqualTo(409);
        ResponseEntity<String> late = complete(attemptId, token, "SUCCESS",
                objectMapper.createObjectNode().put("answer", "late"), eventIdsOf(lateEvents));
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("STALE_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE experiment_id = ?",
                String.class, experimentId)).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id IN"
                + " (SELECT id FROM evaluation_runs WHERE experiment_id = ?)", experimentId))
                .as("取消后不应留下待评测 Job").isZero();
    }

    @Test
    @DisplayName("事件幂等：重发同 ID 同内容返回 DUPLICATE，同 ID 异内容整批 409")
    void eventsShouldBeIdempotentAndRejectConflictingReuse() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();

        List<Map<String, Object>> events = sampleEvents();
        ResponseEntity<String> firstResponse = appendEventsRaw(attemptId, token, events);
        assertThat(firstResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(ackStatuses(firstResponse)).containsOnly("ACCEPTED");
        List<String> first = eventIdsOf(events);

        // 网络重发：同一个 eventId 同一个正文
        ResponseEntity<String> replay = appendEventsRaw(attemptId, token, events);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(ackStatuses(replay)).containsOnly("DUPLICATE");
        assertThat(data(replay).path("items").findValuesAsText("eventId")).containsExactlyElementsOf(first);
        assertThat(count("SELECT COUNT(*) FROM trace_events WHERE run_attempt_id = ?", attemptId))
                .isEqualTo(events.size());

        // 同一个 eventId 换内容：整批拒绝，不静默覆盖证据
        Map<String, Object> conflicting = Map.of(
                "eventId", first.get(0),
                "eventType", "RUN_STARTED",
                "sequenceNo", 1,
                "payload", Map.of("runnerType", "CODEX"));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.set("events", objectMapper.valueToTree(List.of(conflicting)));
        ResponseEntity<String> rejected = workerPost(
                "/internal/v1/run-attempts/" + attemptId + "/events", body);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(rejected)).isEqualTo("RESOURCE_CONFLICT");
    }

    @Test
    @DisplayName("Artifact：必须持有效租约上传，完成时只能引用本 Run 的 Artifact")
    void artifactUploadShouldBeBoundToLeaseAndRun() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        String token = task.path("leaseToken").asText();

        ResponseEntity<String> uploaded = uploadArtifact(runId, attemptId, token, "mock output");
        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        String artifactId = dataId(uploaded);
        assertThat(count("SELECT COUNT(*) FROM artifacts WHERE run_id = ? AND status = 'READY'", runId))
                .isEqualTo(1);

        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.put("taskOutcome", "SUCCESS");
        body.put("outputArtifactId", artifactId);
        body.put("expectedEventCount", eventIds.size());
        body.put("eventManifestSha256", manifest(eventIds));
        ResponseEntity<String> completed = workerPost(
                "/internal/v1/run-attempts/" + attemptId + "/complete", body);
        assertThat(completed.getStatusCode().value())
                .as("完成应成功: %s", completed.getBody()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT output_artifact_id FROM evaluation_runs WHERE id = ?",
                String.class, runId)).isEqualTo(artifactId);

        // 错误令牌上传：拒绝，不产生第二条 Artifact
        ResponseEntity<String> rejected = uploadArtifact(runId, attemptId, "wrong-token", "x");
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM artifacts WHERE run_id = ?", runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("事件清单摘要不符时拒绝完成，Run 仍可继续补事件")
    void completionShouldRejectIncompleteEventManifest() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.put("taskOutcome", "SUCCESS");
        body.put("expectedEventCount", eventIds.size() + 1);
        body.put("eventManifestSha256", manifest(eventIds));
        ResponseEntity<String> rejected = workerPost(
                "/internal/v1/run-attempts/" + attemptId + "/complete", body);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(rejected)).isEqualTo("RESOURCE_CONFLICT");

        // 修正清单后仍能完成：失败的回调没有把 Attempt 写成终态
        ResponseEntity<String> completed = complete(attemptId, token, "SUCCESS",
                objectMapper.createObjectNode().put("answer", "ok"), eventIds);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("Worker 鉴权与能力声明：无凭证 401，未注册 Runner 400")
    void workerAuthAndCapabilitiesShouldBeValidated() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("workerId", "it-worker-1");
        body.putArray("capabilities").add("RUN:MOCK");
        body.put("maxTasks", 1);

        assertThat(workerPostNoAuth("/internal/v1/work/claim", body).getStatusCode().value())
                .isEqualTo(401);

        ObjectNode codex = body.deepCopy();
        codex.putArray("capabilities").add("RUN:CODEX");
        ResponseEntity<String> notRegistered = workerPost("/internal/v1/work/claim", codex);
        assertThat(notRegistered.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(notRegistered).path("field").asText()).isEqualTo("capabilities");

        ObjectNode malformed = body.deepCopy();
        malformed.putArray("capabilities").add("MOCK");
        assertThat(workerPost("/internal/v1/work/claim", malformed).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("提交校验：未发布数据集版本不能开始，实验保持 DRAFT")
    void startShouldRejectDraftDatasetVersion() {
        Scenario scenario = seedPublished(1);
        String datasetId = jdbc.queryForObject(
                "SELECT dataset_id FROM dataset_versions WHERE id = ?", String.class,
                scenario.datasetVersionId());
        String draftVersionId = dataId(post("/api/v1/datasets/" + datasetId + "/versions",
                "it-draft-" + scenario.suffix(), "{\"description\":\"draft\"}"));
        assertThat(post("/api/v1/dataset-versions/" + draftVersionId + "/cases",
                "it-draft-case-" + scenario.suffix(),
                "{\"caseKey\":\"case-1\",\"taskType\":\"AGENT_TASK\",\"input\":{\"prompt\":\"x\"}}")
                .getStatusCode().value()).isEqualTo(201);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "草稿数据实验-" + scenario.suffix());
        body.put("mode", "ONLINE");
        body.put("datasetVersionId", draftVersionId);
        body.put("evaluationPlanVersionId", scenario.planVersionId());
        body.putArray("targets").addObject()
                .put("targetKey", "t1").put("displayName", "T1")
                .put("agentVersionId", scenario.agentVersionId()).put("repeatCount", 1);
        String experimentId = dataId(post("/api/v1/experiments",
                "it-exp-draft-" + scenario.suffix(), body.toString()));

        ResponseEntity<String> rejected = post("/api/v1/experiments/" + experimentId + "/start",
                "start-draft-" + scenario.suffix(), "{}");
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(rejected).path("field").asText()).isEqualTo("datasetVersionId");
        assertThat(jdbc.queryForObject("SELECT status FROM experiments WHERE id = ?", String.class,
                experimentId)).isEqualTo("DRAFT");
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isZero();
    }

    @Test
    @DisplayName("Run 总数超过 10000 时拒绝开始，不写任何槽位")
    void startShouldRejectRunCountAboveLimit() {
        Scenario scenario = seedPublished(1);
        String datasetVersionId = scenario.datasetVersionId();
        // 直接补 500 条样本（绕过导入接口）：本用例验证的是提交上限，不是导入
        jdbc.batchUpdate("INSERT INTO dataset_cases (id, dataset_version_id, case_key, task_type,"
                        + " input_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                java.util.stream.IntStream.rangeClosed(2, 501).mapToObj(i -> new Object[]{
                        Ulid.newId(), datasetVersionId, "bulk-" + i, "AGENT_TASK",
                        "{\"prompt\":\"bulk\"}", Timestamp.from(Instant.now()),
                        Timestamp.from(Instant.now())}).toList());

        String experimentId = createExperiment(scenario, 1, 20);
        ResponseEntity<String> rejected = post("/api/v1/experiments/" + experimentId + "/start",
                "start-too-many-" + scenario.suffix(), "{}");
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(rejected).path("field").asText()).isEqualTo("targets");
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId))
                .isZero();
    }

    @Test
    @DisplayName("创建实验：重复 targetKey 与越界 repeatCount 都被拒绝")
    void experimentCreationShouldValidateTargets() {
        Scenario scenario = seedPublished(1);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "非法实验-" + scenario.suffix());
        body.put("mode", "ONLINE");
        body.put("datasetVersionId", scenario.datasetVersionId());
        body.put("evaluationPlanVersionId", scenario.planVersionId());
        var targets = body.putArray("targets");
        targets.addObject().put("targetKey", "same").put("displayName", "A")
                .put("agentVersionId", scenario.agentVersionId()).put("repeatCount", 1);
        targets.addObject().put("targetKey", "same").put("displayName", "B")
                .put("agentVersionId", scenario.agentVersionId()).put("repeatCount", 1);
        ResponseEntity<String> duplicate = post("/api/v1/experiments",
                "it-exp-dup-" + scenario.suffix(), body.toString());
        assertThat(duplicate.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(duplicate).path("field").asText()).contains("targetKey");

        body.putArray("targets").removeAll();
        body.putArray("targets").addObject().put("targetKey", "t1").put("displayName", "A")
                .put("agentVersionId", scenario.agentVersionId()).put("repeatCount", 21);
        ResponseEntity<String> tooMany = post("/api/v1/experiments",
                "it-exp-repeat-" + scenario.suffix(), body.toString());
        assertThat(tooMany.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(tooMany).path("field").asText()).contains("repeatCount");
    }

    @Test
    @DisplayName("Run 列表与详情暴露执行证据：状态、尝试、事件数、Job 摘要")
    void runReadsShouldExposeExecutionEvidence() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("it-worker-1", 1).get(0);
        String runId = task.path("runId").asText();
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());
        complete(attemptId, token, "SUCCESS", objectMapper.createObjectNode().put("answer", "42"), eventIds);

        JsonNode page = data(get("/api/v1/experiments/" + experimentId + "/runs?status=EVALUATING"));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("caseKey").asText()).isEqualTo("case-1");
        assertThat(page.path("items").get(0).path("attemptCount").asInt()).isEqualTo(1);

        JsonNode detail = data(get("/api/v1/runs/" + runId));
        assertThat(detail.path("attempts").size()).isEqualTo(1);
        assertThat(detail.path("attempts").get(0).path("leaseTokenHash").isMissingNode()).isTrue();
        assertThat(detail.path("jobBatches").get(0).path("pending").asInt()).isEqualTo(1);

        JsonNode jobs = data(get("/api/v1/runs/" + runId + "/evaluation-jobs"));
        assertThat(jobs.path("batches").get(0).path("total").asInt()).isEqualTo(1);
        assertThat(jobs.path("jobs").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(jobs.path("jobs").get(0).path("evaluatorKey").asText()).isNotBlank();

        JsonNode attempts = data(get("/api/v1/runs/" + runId + "/attempts"));
        assertThat(attempts.path("total").asInt()).isEqualTo(1);
        assertThat(attempts.path("items").get(0).path("status").asText()).isEqualTo("COMPLETED");
    }
}
