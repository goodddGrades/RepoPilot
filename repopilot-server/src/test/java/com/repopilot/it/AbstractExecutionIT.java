package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行链路测试基类：用**真实 HTTP** 造出"已发布版本 + 实验 + Worker 协议"的完整前置。
 *
 * <p>为什么不用 {@code Fixtures} 直接插表：TASK-004 的核心是"提交时校验版本状态与数量"，
 * 绕过 API 造数据会把被测的校验逻辑整个跳过。这里只在需要突破上限时用 JDBC 补充。
 */
abstract class AbstractExecutionIT extends AbstractApiIT {

    protected static final String WORKER_TOKEN = "it-worker-token";

    /**
     * 每个用例从**干净的执行状态**开始。
     *
     * <p>为什么需要：领取（{@code /internal/v1/work/claim}）是全局队列语义，
     * 没有"只领我这个实验"的参数——这正是生产要的行为。上一个用例留下的 QUEUED Run
     * 会被下一个用例领走，断言就会指向别人的 Run。清理只覆盖执行域，不动 Agent/数据集/算子
     * 等 TASK-002/003 的种子与用例数据。
     *
     * <p>删除顺序受外键约束：先断开 {@code evaluation_runs} 指向 Attempt 与 Artifact 的列
     * （存在循环引用），再自底向上删。
     */
    @BeforeEach
    void resetExecutionState() {
        // TASK-005：导入回执外键指向 Run，必须在删 Run 之前清掉，
        // 否则上一个用例留下的批次记录会让清理本身失败。
        jdbc.update("DELETE FROM import_records");
        jdbc.update("DELETE FROM import_errors");
        jdbc.update("UPDATE evaluation_runs SET active_attempt_id = NULL, accepted_attempt_id = NULL,"
                + " output_artifact_id = NULL");
        jdbc.update("DELETE FROM annotation_records");
        jdbc.update("DELETE FROM annotation_tasks");
        jdbc.update("DELETE FROM evaluation_results");
        jdbc.update("DELETE FROM evaluation_job_attempts");
        jdbc.update("DELETE FROM evaluation_jobs");
        jdbc.update("DELETE FROM evaluation_batch_runs");
        jdbc.update("DELETE FROM evaluation_batches");
        jdbc.update("DELETE FROM trace_events");
        jdbc.update("DELETE FROM run_attempts");
        // 只删除 Run 侧产物：导入用的 Artifact 没有 run_id，属于 TASK-003 的用例数据
        jdbc.update("DELETE FROM artifacts WHERE run_id IS NOT NULL");
        jdbc.update("DELETE FROM evaluation_runs");
        // Run 删完，批次才没有引用者（evaluation_runs.import_batch_id 反向依赖这里）
        jdbc.update("DELETE FROM import_batches");
    }

    /** 一次可用实验所需的已发布版本。 */
    protected record Scenario(String suffix,
                              String agentVersionId,
                              String datasetVersionId,
                              String planVersionId,
                              String planItemId) {
    }

    protected Scenario seedPublished(int caseCount) {
        String suffix = Ulid.newId().substring(18);

        String agentId = dataId(post("/api/v1/agents", "it-agent-" + suffix,
                """
                {"code":"agent-%s","name":"执行测试 Agent","agentType":"MOCK"}
                """.formatted(suffix)));
        String agentVersionId = dataId(post("/api/v1/agents/" + agentId + "/versions",
                "it-agent-version-" + suffix,
                """
                {"runnerType":"MOCK","config":{"model":"mock-model","temperature":0}}
                """));
        assertThat(post("/api/v1/agent-versions/" + agentVersionId + "/publish",
                "it-agent-publish-" + suffix, "{}").getStatusCode().value()).isEqualTo(200);

        String datasetId = dataId(post("/api/v1/datasets", "it-dataset-" + suffix,
                """
                {"code":"ds-%s","name":"执行测试数据集"}
                """.formatted(suffix)));
        String datasetVersionId = dataId(post("/api/v1/datasets/" + datasetId + "/versions",
                "it-dataset-version-" + suffix,
                """
                {"description":"v1"}
                """));
        for (int i = 1; i <= caseCount; i++) {
            ResponseEntity<String> created = post(
                    "/api/v1/dataset-versions/" + datasetVersionId + "/cases",
                    "it-case-" + suffix + "-" + i,
                    """
                    {"caseKey":"case-%d","taskType":"AGENT_TASK",
                     "input":{"prompt":"请回答第 %d 题"},"expected":{"answer":"42"}}
                    """.formatted(i, i));
            assertThat(created.getStatusCode().value()).isEqualTo(201);
        }
        assertThat(post("/api/v1/dataset-versions/" + datasetVersionId + "/publish",
                "it-dataset-publish-" + suffix, "{}").getStatusCode().value()).isEqualTo(200);

        String evaluatorId = evaluatorId("output.non_empty");
        String planId = dataId(post("/api/v1/evaluation-plans", "it-plan-" + suffix,
                """
                {"code":"plan-%s","name":"执行测试方案"}
                """.formatted(suffix)));
        ResponseEntity<String> planVersion = post("/api/v1/evaluation-plans/" + planId + "/versions",
                "it-plan-version-" + suffix,
                """
                {"config":{"threshold":0.5},"items":[{"evaluatorDefinitionId":"%s",
                 "executionOrder":1,"required":true,"config":{}}]}
                """.formatted(evaluatorId));
        String planVersionId = dataId(planVersion);
        String planItemId = data(planVersion).path("items").path(0).path("id").asText();
        assertThat(post("/api/v1/evaluation-plan-versions/" + planVersionId + "/publish",
                "it-plan-publish-" + suffix, "{}").getStatusCode().value()).isEqualTo(200);

        return new Scenario(suffix, agentVersionId, datasetVersionId, planVersionId, planItemId);
    }

    protected String evaluatorId(String key) {
        JsonNode page = data(get("/api/v1/evaluators?page=1&pageSize=100&key=" + key));
        JsonNode items = page.path("items");
        assertThat(items.isArray()).as("算子清单应已由种子导入").isTrue();
        assertThat(items.size()).as("算子 " + key + " 应存在").isGreaterThan(0);
        return items.get(0).path("id").asText();
    }

    protected String createExperiment(Scenario scenario, int targetCount, int repeatCount) {
        ArrayNode targets = objectMapper.createArrayNode();
        for (int i = 1; i <= targetCount; i++) {
            targets.addObject()
                    .put("targetKey", "target-" + i)
                    .put("displayName", "Target " + i)
                    .put("agentVersionId", scenario.agentVersionId())
                    .put("repeatCount", repeatCount);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "实验-" + scenario.suffix() + "-" + targetCount + "x" + repeatCount);
        body.put("mode", "ONLINE");
        body.put("datasetVersionId", scenario.datasetVersionId());
        body.put("evaluationPlanVersionId", scenario.planVersionId());
        body.putObject("config").putObject("budget").put("maxCostUsd", 1.0);
        body.set("targets", targets);

        ResponseEntity<String> created = post("/api/v1/experiments",
                "it-exp-" + scenario.suffix() + "-" + targetCount + "x" + repeatCount, body.toString());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return dataId(created);
    }

    protected ResponseEntity<String> start(String experimentId, String key) {
        ResponseEntity<String> response = post("/api/v1/experiments/" + experimentId + "/start", key, "{}");
        assertThat(response.getStatusCode().value())
                .as("start 应返回 202，实际: %s", response.getBody()).isEqualTo(202);
        return response;
    }

    protected List<JsonNode> claim(String workerId, int maxTasks) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("workerId", workerId);
        body.putArray("capabilities").add("RUN:MOCK");
        body.put("maxTasks", maxTasks);
        ResponseEntity<String> response = workerPost("/internal/v1/work/claim", body);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<JsonNode> tasks = new ArrayList<>();
        data(response).path("tasks").forEach(tasks::add);
        return tasks;
    }

    protected ResponseEntity<String> workerPost(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(WORKER_TOKEN);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> workerPostNoAuth(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 上传一批事件并返回后端确认的 eventId（顺序与请求一致）。 */
    protected List<String> appendEvents(String attemptId, String token, List<Map<String, Object>> events) {
        ResponseEntity<String> response = appendEventsRaw(attemptId, token, events);
        assertThat(response.getStatusCode().value())
                .as("事件写入应成功: %s", response.getBody()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        data(response).path("items").forEach(item -> ids.add(item.path("eventId").asText()));
        return ids;
    }

    /** 不做状态码断言的原始调用：用于"应当被拒绝"的场景。 */
    protected ResponseEntity<String> appendEventsRaw(String attemptId, String token,
                                                     List<Map<String, Object>> events) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.set("events", objectMapper.valueToTree(events));
        return workerPost("/internal/v1/run-attempts/" + attemptId + "/events", body);
    }

    protected ResponseEntity<String> heartbeat(String attemptId, String token) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        return workerPost("/internal/v1/run-attempts/" + attemptId + "/heartbeat", body);
    }

    /** 请求里带的 eventId（不经过服务端确认），用于"整批被拒"后的断言。 */
    protected static List<String> eventIdsOf(List<Map<String, Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("eventId"))).toList();
    }

    /** 服务端对每条事件的判定：ACCEPTED / DUPLICATE。 */
    protected List<String> ackStatuses(ResponseEntity<String> response) {
        List<String> statuses = new ArrayList<>();
        data(response).path("items").forEach(item -> statuses.add(item.path("status").asText()));
        return statuses;
    }

    protected static String manifest(List<String> eventIds) {
        List<String> sorted = new ArrayList<>(eventIds);
        sorted.sort(String::compareTo);
        return Sha256.ofUtf8(String.join("\n", sorted));
    }

    protected ResponseEntity<String> complete(String attemptId, String token, String outcome,
                                              JsonNode output, List<String> eventIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.put("taskOutcome", outcome);
        body.set("output", output);
        body.putObject("usage").put("totalTokens", 12);
        body.put("expectedEventCount", eventIds.size());
        body.put("eventManifestSha256", manifest(eventIds));
        return workerPost("/internal/v1/run-attempts/" + attemptId + "/complete", body);
    }

    protected ResponseEntity<String> fail(String attemptId, String token, String code, boolean retryable) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", token);
        body.put("errorCode", code);
        body.put("errorMessage", "测试失败: " + code);
        body.put("retryableHint", retryable);
        return workerPost("/internal/v1/run-attempts/" + attemptId + "/fail", body);
    }

    protected ResponseEntity<String> uploadArtifact(String runId, String attemptId, String token,
                                                    String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(WORKER_TOKEN);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("runId", runId);
        form.add("attemptId", attemptId);
        form.add("leaseToken", token);
        form.add("artifactType", "OUTPUT");
        form.add("file", new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return "output.txt";
            }
        });
        return restTemplate.exchange("/internal/v1/artifacts", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

    /**
     * 标准五个事件（与服务端注册表的必填字段一致）。
     *
     * <p>eventId 与 occurredAt 都由"Worker"生成并在重发时保持不变——服务端把它们都计入
     * eventHash，重发时换掉任何一个都会被判成"同 ID 异内容"而不是重放。
     */
    protected List<Map<String, Object>> sampleEvents() {
        String at = "2026-10-18T00:00:00.000Z";
        return List.of(
                Map.of("eventId", Ulid.newId(), "eventType", "RUN_STARTED", "sequenceNo", 1,
                        "occurredAt", at, "payload", Map.of("runnerType", "MOCK")),
                Map.of("eventId", Ulid.newId(), "eventType", "MODEL_REQUEST", "sequenceNo", 2,
                        "occurredAt", at, "payload", Map.of("model", "mock-model", "requestSummary", "q")),
                Map.of("eventId", Ulid.newId(), "eventType", "MODEL_RESPONSE", "sequenceNo", 3,
                        "occurredAt", at, "payload", Map.of("model", "mock-model", "outputSummary", "a")),
                Map.of("eventId", Ulid.newId(), "eventType", "OUTPUT_PRODUCED", "sequenceNo", 4,
                        "occurredAt", at, "payload", Map.of("outputSummary", "answer")),
                Map.of("eventId", Ulid.newId(), "eventType", "RUN_COMPLETED", "sequenceNo", 5,
                        "occurredAt", at, "payload", Map.of("taskOutcome", "SUCCESS"))
        );
    }

    protected String runIdOf(String experimentId, String status) {
        return jdbc.queryForObject("SELECT id FROM evaluation_runs WHERE experiment_id = ? AND status = ?"
                + " ORDER BY created_at, id LIMIT 1", String.class, experimentId, status);
    }
}
