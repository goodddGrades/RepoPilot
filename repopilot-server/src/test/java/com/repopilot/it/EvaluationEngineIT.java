package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.modules.evaluation.service.EvaluationBatchService;
import com.repopilot.modules.evaluation.service.JobAttemptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-007 主链路验收：从已封口 Trace 到 Evaluation Result。
 *
 * <p>全部走真实 HTTP 与真实 MySQL，不 mock 服务层：这些用例同时验证了
 * 领取 SQL（{@code FOR UPDATE ... SKIP LOCKED} + 串行条件）、租约摘要、
 * 完成回执的幂等、证据归属校验与四级状态收口的事务边界。
 *
 * <p>Worker 一侧的算子实现由 {@code repopilot-worker/tests} 覆盖；这里扮演 Worker：
 * 领取任务、读取服务端下发的快照、按算子语义提交结论。因此"提交什么"是被测契约的一部分，
 * 断言都落在**持久化结果**与**状态机**上，而不是 HTTP 200。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-007 评测执行引擎")
class EvaluationEngineIT extends AbstractOfflineIT {

    @Autowired
    private JobAttemptService jobAttemptService;

    @Autowired
    private EvaluationBatchService batchService;

    // ================================================================== ① 有效 Job

    @Test
    @DisplayName("有效 Job 领取输入完整、提交后结果持久化、Job/Run/批次依次收口")
    void validJobRunsAndPersistsResult() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));

        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        String runAttemptId = runTask.path("attemptId").asText();
        List<String> eventIds = appendEvents(runAttemptId, runTask.path("leaseToken").asText(),
                sampleEvents());
        assertThat(completeRun(runAttemptId, runTask.path("leaseToken").asText(), "SUCCESS",
                "{\"answer\":\"42\"}", eventIds).getStatusCode().value()).isEqualTo(200);

        // 封口即进入评测：Job 已生成但还没有结果
        assertThat(runStatus(runId)).isEqualTo("EVALUATING");
        String jobId = singleJobId(runId);
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        assertThat(resultCount(jobId)).isZero();

        // 只声明评测能力的 Worker 只能领到评测任务，不会拿到 Agent 执行任务
        List<JsonNode> tasks = claimAs(workerId("eval"), "EVAL:output.non_empty");
        assertThat(tasks).as("同一批次只有一个 Job").hasSize(1);
        JsonNode task = tasks.get(0);
        assertThat(task.path("kind").asText()).isEqualTo("EVALUATION");
        assertThat(task.path("runId").asText()).isEqualTo(runId);
        assertThat(task.path("jobId").asText()).isEqualTo(jobId);
        assertThat(task.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(task.path("leaseToken").asText()).isNotBlank();

        JsonNode input = task.path("input");
        assertThat(input.path("jobId").asText()).isEqualTo(jobId);
        assertThat(input.path("batchId").asText()).isEqualTo(batchIdOf(jobId));
        assertThat(input.path("executionOrder").asInt()).isEqualTo(1);
        assertThat(input.path("required").asBoolean()).isTrue();
        assertThat(input.path("evaluator").path("key").asText()).isEqualTo("output.non_empty");
        assertThat(input.path("evaluator").path("version").asInt()).isEqualTo(1);
        assertThat(input.path("evaluator").path("type").asText()).isEqualTo("RULE");
        assertThat(input.path("config").path("outputPath").asText()).isEqualTo("answer");

        JsonNode snapshot = input.path("acceptedRunSnapshot");
        assertThat(snapshot.path("mode").asText()).isEqualTo("ONLINE");
        assertThat(snapshot.path("taskOutcome").asText()).isEqualTo("SUCCESS");
        assertThat(snapshot.path("acceptedAttemptId").asText()).isEqualTo(runAttemptId);
        assertThat(snapshot.path("output").path("answer").asText()).isEqualTo("42");
        assertThat(snapshot.path("datasetCase").path("expected").path("answer").asText())
                .as("算子需要 ground truth 时从快照取，不另行查库").isEqualTo("42");
        assertThat(snapshot.path("trace").path("sealed").asBoolean()).isTrue();
        assertThat(snapshot.path("trace").path("eventCount").asInt()).isEqualTo(eventIds.size());
        assertThat(snapshot.path("trace").path("manifestSha256").asText()).isEqualTo(manifest(eventIds));

        // 快照里的轨迹地址必须真能读到同样条数：否则 Worker 拿到的"清单"无法核对
        JsonNode page = data(get(snapshot.path("trace").path("eventsUrl").asText()));
        assertThat(page.path("items").size()).isEqualTo(eventIds.size());
        assertThat(page.path("hasMore").asBoolean()).isFalse();

        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        ResponseEntity<String> completed = jobComplete(attemptId, token, "PASS", new BigDecimal("1.0"),
                "输出非空", "[{\"type\":\"outputPath\",\"path\":\"answer\"}]",
                "{\"outputPath\":\"answer\",\"empty\":false}", "RULE");
        assertThat(completed.getStatusCode().value())
                .as("提交结论: %s", completed.getBody()).isEqualTo(200);
        assertThat(codeOf(completed)).isEqualTo("OK");

        // 结果、Job、Attempt、Run、批次五处必须同时成立
        Map<String, Object> result = jdbc.queryForMap(
                "SELECT verdict, score, reason, generated_by, job_attempt_id, evidence_json"
                        + " FROM evaluation_results WHERE job_id = ?", jobId);
        assertThat(result.get("verdict")).isEqualTo("PASS");
        assertThat(((BigDecimal) result.get("score"))).isEqualByComparingTo("1.00000");
        assertThat(result.get("generated_by")).isEqualTo("RULE");
        assertThat(result.get("job_attempt_id")).isEqualTo(attemptId);
        assertThat(result.get("reason")).isEqualTo("输出非空");
        assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE id = ? AND finished_at IS NOT NULL",
                jobId)).isEqualTo(1);
        assertThat(attemptStatus(attemptId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_job_attempts WHERE id = ?"
                + " AND completion_hash IS NOT NULL AND finished_at IS NOT NULL", attemptId)).isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE id = ? AND finished_at IS NOT NULL",
                runId)).isEqualTo(1);
        assertThat(evaluationBatchStatus(batchIdOf(jobId))).isEqualTo("COMPLETED");

        // 前端读到的就是库里这份结果
        JsonNode detail = data(get("/api/v1/runs/" + runId));
        assertThat(detail.path("run").path("status").asText()).isEqualTo("COMPLETED");
        JsonNode jobView = detail.path("jobs").get(0);
        assertThat(jobView.path("id").asText()).isEqualTo(jobId);
        assertThat(jobView.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jobView.path("evaluatorType").asText()).isEqualTo("RULE");
        assertThat(jobView.path("result").path("verdict").asText()).isEqualTo("PASS");
        assertThat(jobView.path("result").path("score").decimalValue()).isEqualByComparingTo("1.0");
        assertThat(jobView.path("result").path("evidence").get(0).path("type").asText())
                .isEqualTo("outputPath");
        assertThat(jobView.path("attempts").get(0).path("workerId").asText()).startsWith("it-eval");
        assertThat(jobView.path("attempts").get(0).path("status").asText()).isEqualTo("COMPLETED");

        // 批次视图的计数与库内一致
        JsonNode batch = data(get("/api/v1/evaluation-batches/" + batchIdOf(jobId)));
        assertThat(batch.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.path("triggerType").asText()).isEqualTo("INITIAL");
        assertThat(batch.path("expectedJobCount").asLong()).isEqualTo(1);
        assertThat(batch.path("runCount").asLong()).isEqualTo(1);
        assertThat(batch.path("jobs").path("total").asLong()).isEqualTo(1);
        assertThat(batch.path("jobs").path("completed").asLong()).isEqualTo(1);
    }

    // ================================================================== ② 算子语义

    @Test
    @DisplayName("四种 verdict 各自落库：FAIL 是质量结论而非执行失败，批次仍是 COMPLETED")
    void verdictsHaveDistinctSemantics() {
        String planVersionId = publishPlan(List.of(
                item("answer.exact_match", 1,
                        "{\"outputPath\":\"answer\",\"expectedPath\":\"answer\"}"),
                item("cost.budget", 2, "{\"maxCost\":0.05,\"currency\":\"USD\"}"),
                item("trace.tool_error", 3, "{}"),
                item("output.non_empty", 4, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));

        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        String token = runTask.path("leaseToken").asText();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), sampleEvents());
        // usage 只有 totalTokens：没有成本、没有币种
        assertThat(completeRun(runTask.path("attemptId").asText(), token, "SUCCESS",
                "{\"answer\":\"42\"}", eventIds).getStatusCode().value()).isEqualTo(200);

        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ?", runId)).isEqualTo(4);

        // 串行：order=1 未终态前，后面的算子不可领取
        List<JsonNode> firstWave = claimAs(workerId("eval"), "EVAL:answer.exact_match",
                "EVAL:cost.budget", "EVAL:trace.tool_error", "EVAL:output.non_empty");
        assertThat(firstWave).hasSize(1);
        assertThat(firstWave.get(0).path("input").path("executionOrder").asInt()).isEqualTo(1);

        // order=1：精确匹配通过
        completeClaimed(firstWave.get(0), "PASS", new BigDecimal("1.0"), "与期望值完全一致",
                "[{\"type\":\"casePath\",\"path\":\"expected.answer\"},{\"type\":\"outputPath\",\"path\":\"answer\"}]",
                "{\"expected\":\"42\",\"actual\":\"42\"}", "RULE");

        // order=2：没有成本与币种 → NOT_APPLICABLE，分数为 NULL 而不是 0
        JsonNode second = single(claimAs(workerId("eval"), "EVAL:cost.budget"));
        assertThat(second.path("input").path("executionOrder").asInt()).isEqualTo(2);
        completeClaimed(second, "NOT_APPLICABLE", null, "缺少成本数据，不自动按 0 分或通过处理",
                "[]", "{\"costPresent\":false}", "RULE");

        // order=3：轨迹里没有工具观测 → NOT_APPLICABLE，不能凭"没看到失败"判 PASS
        JsonNode third = single(claimAs(workerId("eval"), "EVAL:trace.tool_error"));
        assertThat(third.path("input").path("executionOrder").asInt()).isEqualTo(3);
        completeClaimed(third, "NOT_APPLICABLE", null, "轨迹中没有工具调用观测，不能据此判定通过",
                "[{\"type\":\"event\",\"eventId\":\"" + eventIds.get(3) + "\"}]",
                "{\"toolCalls\":0,\"failures\":[]}", "RULE");

        // order=4：质量失败 → verdict=FAIL，但 Job 仍然 COMPLETED
        JsonNode fourth = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        completeClaimed(fourth, "FAIL", new BigDecimal("0.0"), "输出为空",
                "[{\"type\":\"outputPath\",\"path\":\"answer\"}]",
                "{\"outputPath\":\"answer\",\"empty\":true}", "RULE");

        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ? AND status = 'COMPLETED'",
                runId)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT verdict FROM evaluation_results r"
                + " JOIN evaluation_jobs j ON j.id = r.job_id WHERE j.run_id = ?"
                + " ORDER BY r.created_at", String.class, runId))
                .containsExactly("PASS", "NOT_APPLICABLE", "NOT_APPLICABLE", "FAIL");
        // NOT_APPLICABLE 的分数必须是 NULL：写 0 会被汇总成"答错了"
        assertThat(count("SELECT COUNT(*) FROM evaluation_results r JOIN evaluation_jobs j"
                + " ON j.id = r.job_id WHERE j.run_id = ? AND r.verdict = 'NOT_APPLICABLE'"
                + " AND r.score IS NULL", runId)).isEqualTo(2);
        // 质量 FAIL 不影响收口：Run 仍然评测完成
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(evaluationBatchStatus(batchIdOfRun(runId))).isEqualTo("COMPLETED");
        // 所有 Job 都 COMPLETED，没有任何技术失败被混进结果
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ? AND error_code IS NOT NULL",
                runId)).isZero();
    }

    @Test
    @DisplayName("提交越界分数、错误 generatedBy 与非对象 result 都被拒绝，且不落库")
    void invalidSubmissionIsRejectedWithoutSideEffects() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), sampleEvents());
        completeRun(runTask.path("attemptId").asText(), runTask.path("leaseToken").asText(),
                "SUCCESS", "{\"answer\":\"42\"}", eventIds);

        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();

        ResponseEntity<String> outOfRange = jobComplete(attemptId, token, "PASS",
                new BigDecimal("1.5"), "越界", "[]", "{\"ok\":true}", "RULE");
        assertThat(outOfRange.getStatusCode().value()).isEqualTo(400);
        assertThat(codeOf(outOfRange)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(outOfRange).path("field").asText()).isEqualTo("score");

        ResponseEntity<String> badScale = jobComplete(attemptId, token, "PASS",
                new BigDecimal("0.123456"), "精度过高", "[]", "{\"ok\":true}", "RULE");
        assertThat(badScale.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(badScale).path("field").asText()).isEqualTo("score");

        // RULE 算子的结果不能标成 LLM：否则前端会把它当成模型判断
        ResponseEntity<String> wrongGenerator = jobComplete(attemptId, token, "PASS",
                new BigDecimal("1.0"), "错标来源", "[]", "{\"ok\":true}", "LLM");
        assertThat(wrongGenerator.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(wrongGenerator).path("field").asText()).isEqualTo("generatedBy");

        ResponseEntity<String> missingResult = jobComplete(attemptId, token, "PASS",
                new BigDecimal("1.0"), "缺结果体", "[]", null, "RULE");
        assertThat(missingResult.getStatusCode().value()).isEqualTo(400);

        // 四次拒绝之后 Attempt 仍然可用，Job 仍然是 RUNNING
        assertThat(attemptStatus(attemptId)).isEqualTo("RUNNING");
        assertThat(jobStatus(singleJobId(runId))).isEqualTo("RUNNING");
        assertThat(resultCount(singleJobId(runId))).isZero();

        // 一次合法提交仍然成功——拒绝不是因为把状态弄坏了
        assertThat(jobComplete(attemptId, token, "PASS", new BigDecimal("1.0"), "输出非空",
                "[]", "{\"ok\":true}", "RULE").getStatusCode().value()).isEqualTo(200);
        assertThat(resultCount(singleJobId(runId))).isEqualTo(1);
    }

    // ================================================================== ③ 无效配置/残缺轨迹

    @Test
    @DisplayName("算子配置错误是技术失败：Job FAILED、无结果、Run 与批次收口为失败")
    void invalidOperatorConfigFailsTechnically() {
        // 方案发布只校验"配置是对象"，不按算子的 inputSchema 校验——这是刻意的：
        // 方案是配置快照，语法校验在算子执行时由 Worker 做，服务端不复制一份 schema 引擎。
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":123}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), sampleEvents());
        completeRun(runTask.path("attemptId").asText(), runTask.path("leaseToken").asText(),
                "SUCCESS", "{\"answer\":\"42\"}", eventIds);

        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        String attemptId = task.path("attemptId").asText();
        ResponseEntity<String> failed = jobFail(attemptId, task.path("leaseToken").asText(),
                "EVALUATOR_CONFIG_INVALID", false);
        assertThat(failed.getStatusCode().value()).as("失败回执: %s", failed.getBody()).isEqualTo(200);
        assertThat(data(failed).path("retryScheduled").asBoolean())
                .as("配置错误重试多少次都一样").isFalse();

        String jobId = singleJobId(runId);
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM evaluation_jobs WHERE id = ?",
                String.class, jobId)).isEqualTo("EVALUATOR_CONFIG_INVALID");
        assertThat(resultCount(jobId))
                .as("技术失败不能留下一条伪造的结论").isZero();
        assertThat(attemptStatus(attemptId)).isEqualTo("FAILED");
        assertThat(runStatus(runId))
                .as("required 算子技术失败 → Run 评测失败").isEqualTo("FAILED");
        assertThat(evaluationBatchStatus(batchIdOf(jobId))).isEqualTo("FAILED");

        // 前端需要看到"为什么失败"，而不是一个空白结果
        JsonNode detail = data(get("/api/v1/runs/" + runId));
        JsonNode jobView = detail.path("jobs").get(0);
        assertThat(jobView.path("status").asText()).isEqualTo("FAILED");
        assertThat(jobView.path("errorCode").asText()).isEqualTo("EVALUATOR_CONFIG_INVALID");
        assertThat(jobView.path("result").isMissingNode() || jobView.path("result").isNull())
                .as("没有结果时不能回一个空对象冒充结果").isTrue();
        assertThat(jobView.path("attempts").get(0).path("errorCode").asText())
                .isEqualTo("EVALUATOR_CONFIG_INVALID");
    }

    @Test
    @DisplayName("轨迹不完整时报不适用而不是通过；证据必须属于本 Run")
    void incompleteTraceAndForeignEvidenceAreHandledHonestly() {
        String planVersionId = publishPlan(List.of(
                item("trace.tool_error", 1, "{}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();

        // 只有一台"没有工具调用"的轨迹：RUN_STARTED + MODEL_* + OUTPUT + RUN_COMPLETED
        List<Map<String, Object>> events = sampleEvents();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), events);
        completeRun(runTask.path("attemptId").asText(), runTask.path("leaseToken").asText(),
                "SUCCESS", "{\"answer\":\"42\"}", eventIds);

        JsonNode task = single(claimAs(workerId("eval"), "EVAL:trace.tool_error"));
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        assertThat(task.path("input").path("acceptedRunSnapshot").path("trace").path("eventCount").asInt())
                .isEqualTo(eventIds.size());

        // 引用一条不存在的事件 = 编造证据
        String foreignEventId = Ulid.newId();
        ResponseEntity<String> forged = jobComplete(attemptId, token, "NOT_APPLICABLE", null,
                "轨迹中没有工具调用观测", "[{\"type\":\"event\",\"eventId\":\"" + foreignEventId + "\"}]",
                "{\"toolCalls\":0}", "RULE");
        assertThat(forged.getStatusCode().value()).isEqualTo(400);
        assertThat(codeOf(forged)).isEqualTo("VALIDATION_ERROR");
        assertThat(forged.getBody()).contains("不属于本 Run");
        assertThat(resultCount(singleJobId(runId))).isZero();

        // 换成真实事件后通过；结论仍然是"不适用"，不是 PASS
        ResponseEntity<String> honest = jobComplete(attemptId, token, "NOT_APPLICABLE", null,
                "轨迹中没有工具调用观测，不能据此判定通过",
                "[{\"type\":\"event\",\"eventId\":\"" + eventIds.get(3) + "\"}]",
                "{\"toolCalls\":0,\"failures\":[]}", "RULE");
        assertThat(honest.getStatusCode().value()).as("%s", honest.getBody()).isEqualTo(200);

        JsonNode detail = data(get("/api/v1/runs/" + runId));
        JsonNode result = detail.path("jobs").get(0).path("result");
        assertThat(result.path("verdict").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(isNullOrMissing(result.get("score"))).isTrue();
        assertThat(result.path("result").path("toolCalls").asInt()).isZero();
        assertThat(result.path("evidence").get(0).path("eventId").asText()).isEqualTo(eventIds.get(3));
        // 不适用不是失败：Run 照常完成
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    // ================================================================== ④ 未实现的算子

    @Test
    @DisplayName("未实现算子不会被静默执行：无能力声明领不到，能力拼错直接 400")
    void unimplementedEvaluatorsAreNotSilentlyClaimed() {
        // judge.rubric 属于 TASK-008；本阶段只保证"不会被谁的 Worker 顺手算掉"
        String planVersionId = publishPlan(List.of(
                item("judge.rubric", 1,
                        "{\"rubricKey\":\"quality\",\"model\":\"not-configured\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), sampleEvents());
        completeRun(runTask.path("attemptId").asText(), runTask.path("leaseToken").asText(),
                "SUCCESS", "{\"answer\":\"42\"}", eventIds);

        assertThat(singleJobId(runId)).isNotBlank();
        // 只声明了规则算子的 Worker 领不到 Judge 任务
        assertThat(claimAs(workerId("eval"), "EVAL:output.non_empty")).isEmpty();
        assertThat(jobStatus(singleJobId(runId))).isEqualTo("PENDING");

        // 能力字符串写错时立即 400，而不是"一直领不到"的静默现象
        ObjectNode body = objectMapper.createObjectNode();
        body.put("workerId", workerId("typo"));
        body.putArray("capabilities").add("EVAL:output.non_emty");
        body.put("maxTasks", 1);
        ResponseEntity<String> typo = workerPost("/internal/v1/work/claim", body);
        assertThat(typo.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(typo).path("field").asText()).isEqualTo("capabilities");
        assertThat(typo.getBody()).contains("output.non_emty");
    }

    // ================================================================== ⑤⑩ 幂等与并发

    @Test
    @DisplayName("同一正文重复提交是幂等的：只产生一份结果；换正文则 409")
    void duplicateCompletionIsIdempotent() {
        Running run = preparedOnlineRun();
        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();

        ResponseEntity<String> first = jobComplete(attemptId, token, "PASS", new BigDecimal("1.0"),
                "输出非空", "[]", "{\"empty\":false}", "RULE");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> replay = jobComplete(attemptId, token, "PASS", new BigDecimal("1.0"),
                "输出非空", "[]", "{\"empty\":false}", "RULE");
        assertThat(replay.getStatusCode().value()).as("按正文摘要重放应当成功").isEqualTo(200);
        assertThat(data(replay).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(resultCount(run.jobId())).isEqualTo(1);

        // 同一 Attempt 换一个结论 = 两个不同事实，必须拒绝
        ResponseEntity<String> conflicting = jobComplete(attemptId, token, "FAIL",
                new BigDecimal("0.0"), "输出为空", "[]", "{\"empty\":true}", "RULE");
        assertThat(conflicting.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(conflicting)).isEqualTo("STALE_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT verdict FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId())).isEqualTo("PASS");
        assertThat(resultCount(run.jobId())).isEqualTo(1);

        // 数据库唯一键是最后一层：手工插第二份必须失败
        assertThat(count("SELECT COUNT(DISTINCT job_id) FROM evaluation_results WHERE job_id = ?",
                run.jobId())).isEqualTo(1);
    }

    @Test
    @DisplayName("租约过期由回收器终止；旧执行者晚到的提交被拒绝，不覆盖新尝试")
    void expiredLeaseCannotWriteResults() {
        Running run = preparedOnlineRun();
        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        String firstAttempt = task.path("attemptId").asText();
        String firstToken = task.path("leaseToken").asText();

        // 模拟 Worker 卡死：租约过期
        jdbc.update("UPDATE evaluation_job_attempts SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(30)), firstAttempt);
        assertThat(jobAttemptService.reapExpired()).isEqualTo(1);

        assertThat(attemptStatus(firstAttempt)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM evaluation_job_attempts WHERE id = ?",
                String.class, firstAttempt)).isEqualTo("LEASE_EXPIRED");
        // 还有重试预算：Job 回到"可再领"，但退避未到点前不可领取
        assertThat(jobStatus(run.jobId())).isEqualTo("RUNNING");
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE id = ?"
                + " AND active_attempt_id IS NULL AND next_retry_at IS NOT NULL", run.jobId())).isEqualTo(1);
        jdbc.update("UPDATE evaluation_jobs SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(600)), run.jobId());
        assertThat(claimAs(workerId("eval"), "EVAL:output.non_empty"))
                .as("退避未到点不应重新领取").isEmpty();

        forceRetryDue(run.jobId());
        JsonNode second = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        String secondAttempt = second.path("attemptId").asText();
        assertThat(second.path("attemptNo").asInt()).isEqualTo(2);
        assertThat(secondAttempt).isNotEqualTo(firstAttempt);
        // 旧令牌对同一 Attempt 也失效了（Attempt 已 FAILED）
        assertThat(jobComplete(secondAttempt, firstToken, "PASS", new BigDecimal("1.0"), "旧令牌",
                "[]", "{\"empty\":false}", "RULE").getStatusCode().value()).isEqualTo(409);
        assertThat(jobComplete(firstAttempt, firstToken, "PASS", new BigDecimal("1.0"), "晚到",
                "[]", "{\"empty\":false}", "RULE").getStatusCode().value()).isEqualTo(409);

        assertThat(jobComplete(secondAttempt, second.path("leaseToken").asText(), "PASS",
                new BigDecimal("1.0"), "输出非空", "[]", "{\"empty\":false}", "RULE")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(resultCount(run.jobId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT job_attempt_id FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId())).isEqualTo(secondAttempt);
    }

    // ================================================================== ⑥ 重试

    @Test
    @DisplayName("可重试失败安排下一次尝试；成功后晚到的失败回执不再改动历史")
    void retryAfterFailureKeepsSuccessfulResult() {
        Running run = preparedOnlineRun();

        JsonNode first = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        ResponseEntity<String> scheduled = jobFail(first.path("attemptId").asText(),
                first.path("leaseToken").asText(), "TRACE_FETCH_FAILED", true);
        assertThat(scheduled.getStatusCode().value()).isEqualTo(200);
        assertThat(data(scheduled).path("retryScheduled").asBoolean()).isTrue();
        assertThat(data(scheduled).path("nextRetryAt").isNull()).isFalse();
        assertThat(resultCount(run.jobId())).isZero();
        assertThat(jobStatus(run.jobId())).isEqualTo("RUNNING");

        forceRetryDue(run.jobId());
        JsonNode second = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        assertThat(second.path("attemptNo").asInt()).isEqualTo(2);
        assertThat(jobComplete(second.path("attemptId").asText(), second.path("leaseToken").asText(),
                "PASS", new BigDecimal("1.0"), "输出非空", "[]", "{\"empty\":false}", "RULE")
                .getStatusCode().value()).isEqualTo(200);

        // 第一次尝试的失败回执晚到：Attempt 已完成/被取代，必须拒绝
        ResponseEntity<String> late = jobFail(first.path("attemptId").asText(),
                first.path("leaseToken").asText(), "TRACE_FETCH_FAILED", true);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("STALE_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT verdict FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId())).isEqualTo("PASS");
        assertThat(jobStatus(run.jobId())).isEqualTo("COMPLETED");
        assertThat(runStatus(run.runId())).isEqualTo("COMPLETED");
        // 两次尝试都留在历史里，便于排障
        assertThat(count("SELECT COUNT(*) FROM evaluation_job_attempts WHERE job_id = ?",
                run.jobId())).isEqualTo(2);
        JsonNode detail = data(get("/api/v1/runs/" + run.runId()));
        assertThat(detail.path("jobs").get(0).path("attempts").size()).isEqualTo(2);
        assertThat(detail.path("jobs").get(0).path("attemptCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("重试预算耗尽后 Job 与 Run 判失败，不再无限重试")
    void retryBudgetIsBounded() {
        Running run = preparedOnlineRun();
        // 默认 max-attempts-per-job = 3
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
            assertThat(task.path("attemptNo").asInt()).isEqualTo(attempt);
            ResponseEntity<String> response = jobFail(task.path("attemptId").asText(),
                    task.path("leaseToken").asText(), "TRACE_FETCH_FAILED", true);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(data(response).path("retryScheduled").asBoolean())
                    .as("第 %s 次之后是否还有预算", attempt).isEqualTo(attempt < 3);
            forceRetryDue(run.jobId());
        }
        assertThat(jobStatus(run.jobId())).isEqualTo("FAILED");
        assertThat(attemptStatuses(run.jobId())).containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(resultCount(run.jobId())).isZero();
        assertThat(runStatus(run.runId())).isEqualTo("FAILED");
        assertThat(claimAs(workerId("eval"), "EVAL:output.non_empty")).isEmpty();
    }

    // ================================================================== ⑦⑧ 批次与版本

    @Test
    @DisplayName("一批次内多 Run 多算子：串行推进、逐级收口、计数与库一致")
    void batchAggregatesMultipleRunsAndOperators() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}"),
                item("trace.tool_error", 2, "{}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 2);
        start(experimentId, idem("start"));

        // 1 target × 1 case × 2 重复 = 2 个 Run
        List<JsonNode> runTasks = claimAs(workerId("runner"), "RUN:MOCK");
        assertThat(runTasks).hasSize(2);
        List<String> runIds = new ArrayList<>();
        for (JsonNode task : runTasks) {
            List<String> eventIds = appendEvents(task.path("attemptId").asText(),
                    task.path("leaseToken").asText(), sampleEvents());
            assertThat(completeRun(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                    "SUCCESS", "{\"answer\":\"42\"}", eventIds).getStatusCode().value()).isEqualTo(200);
            runIds.add(task.path("runId").asText());
        }
        assertThat(runIds).hasSize(2);
        String batchId = batchIdOfRun(runIds.get(0));
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE batch_id = ?", batchId)).isEqualTo(4);
        assertThat(evaluationBatchStatus(batchId)).isEqualTo("RUNNING");

        // 两个 order=1 的 Job 可以并行领取，order=2 被串行条件挡住
        List<JsonNode> firstWave = claimAs(workerId("eval"), "EVAL:output.non_empty",
                "EVAL:trace.tool_error");
        assertThat(firstWave).hasSize(2);
        assertThat(firstWave).allSatisfy(task ->
                assertThat(task.path("input").path("executionOrder").asInt()).isEqualTo(1));
        List<JsonNode> afterFirstWave = claimAs(workerId("eval"), "EVAL:trace.tool_error");
        assertThat(afterFirstWave).as("order=1 尚未全部终态时不发 order=2").isEmpty();

        completeClaimed(firstWave.get(0), "PASS", new BigDecimal("1.0"), "输出非空", "[]",
                "{\"empty\":false}", "RULE");
        // 一个 Run 的 order=1 完成、另一个还没完成：批次不能收口，已完成的 Run 也不能先收口
        assertThat(evaluationBatchStatus(batchId)).isEqualTo("RUNNING");
        assertThat(runStatus(runIds.get(0))).isEqualTo("EVALUATING");
        assertThat(runStatus(runIds.get(1))).isEqualTo("EVALUATING");

        completeClaimed(firstWave.get(1), "PASS", new BigDecimal("1.0"), "输出非空", "[]",
                "{\"empty\":false}", "RULE");

        // 两个 Run 的 order=2 现在都可领取
        List<JsonNode> secondWave = claimAs(workerId("eval"), "EVAL:trace.tool_error");
        assertThat(secondWave).hasSize(2);
        assertThat(secondWave).allSatisfy(task ->
                assertThat(task.path("input").path("executionOrder").asInt()).isEqualTo(2));
        completeClaimed(secondWave.get(0), "INDETERMINATE", null, "工具调用未闭合", "[]",
                "{\"unclosed\":[\"c1\"]}", "RULE");
        assertThat(evaluationBatchStatus(batchId)).isEqualTo("RUNNING");
        completeClaimed(secondWave.get(1), "PASS", new BigDecimal("1.0"), "工具调用全部成功", "[]",
                "{\"toolCalls\":1,\"failures\":[]}", "RULE");

        assertThat(evaluationBatchStatus(batchId)).isEqualTo("COMPLETED");
        runIds.forEach(runId -> assertThat(runStatus(runId)).isEqualTo("COMPLETED"));
        JsonNode batch = data(get("/api/v1/evaluation-batches/" + batchId));
        assertThat(batch.path("jobs").path("total").asLong()).isEqualTo(4);
        assertThat(batch.path("jobs").path("completed").asLong()).isEqualTo(4);
        assertThat(batch.path("jobs").path("failed").asLong()).isZero();
        assertThat(batch.path("finishedAt").isNull()).isFalse();

        JsonNode runsPage = data(get("/api/v1/evaluation-batches/" + batchId + "/runs"));
        assertThat(runsPage.path("total").asLong()).isEqualTo(2);
        assertThat(runsPage.path("items").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("部分 Job 技术失败 → 批次 PARTIAL_FAILED，成功的 Run 仍 COMPLETED")
    void partiallyFailedBatchIsDistinguished() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}"),
                item("answer.exact_match", 2,
                        "{\"outputPath\":\"answer\",\"expectedPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 2);
        start(experimentId, idem("start"));
        List<JsonNode> runTasks = claimAs(workerId("runner"), "RUN:MOCK");
        assertThat(runTasks).hasSize(2);
        for (JsonNode task : runTasks) {
            List<String> eventIds = appendEvents(task.path("attemptId").asText(),
                    task.path("leaseToken").asText(), sampleEvents());
            completeRun(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                    "SUCCESS", "{\"answer\":\"42\"}", eventIds);
        }
        String runA = runTasks.get(0).path("runId").asText();
        String runB = runTasks.get(1).path("runId").asText();

        List<JsonNode> firstWave = claimAs(workerId("eval"), "EVAL:output.non_empty");
        assertThat(firstWave).hasSize(2);
        completeClaimed(taskOf(firstWave, runA), "PASS", new BigDecimal("1.0"), "输出非空", "[]",
                "{\"empty\":false}", "RULE");
        // runB 的第一个算子直接技术失败
        JsonNode failingTask = taskOf(firstWave, runB);
        ResponseEntity<String> failed = jobFail(failingTask.path("attemptId").asText(),
                failingTask.path("leaseToken").asText(), "EVALUATOR_CRASHED", false);
        assertThat(failed.getStatusCode().value()).isEqualTo(200);

        List<JsonNode> secondWave = claimAs(workerId("eval"), "EVAL:answer.exact_match");
        assertThat(secondWave).as("技术失败也是终态，后续算子照常执行").hasSize(2);
        completeClaimed(taskOf(secondWave, runA), "PASS", new BigDecimal("1.0"), "与期望值完全一致", "[]",
                "{\"expected\":\"42\",\"actual\":\"42\"}", "RULE");
        completeClaimed(taskOf(secondWave, runB), "PASS", new BigDecimal("1.0"), "与期望值完全一致", "[]",
                "{\"expected\":\"42\",\"actual\":\"42\"}", "RULE");

        String batchId = batchIdOfRun(runA);
        assertThat(evaluationBatchStatus(batchId)).isEqualTo("PARTIAL_FAILED");
        JsonNode batch = data(get("/api/v1/evaluation-batches/" + batchId));
        assertThat(batch.path("jobs").path("completed").asLong()).isEqualTo(3);
        assertThat(batch.path("jobs").path("failed").asLong()).isEqualTo(1);
        // 失败的那个 Run 判失败，另一个照常完成
        assertThat(runStatus(runA)).isEqualTo("COMPLETED");
        assertThat(runStatus(runB)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("同一 Run 用不同方案版本重评：新建批次、旧结果不被覆盖、Run 状态不回退")
    void reevaluationWithAnotherPlanKeepsHistory() {
        Running run = preparedOnlineRun();
        String originalResultId = completeAndReadResultId(run);

        // 第二个方案版本：引用样本里不存在的 ground truth，因此重评结论必然是 NOT_APPLICABLE。
        // 不改样本数据——已封口的样本是历史事实，重评只能换"怎么看"，不能换"看的是什么"。
        String secondPlan = publishPlan(List.of(
                item("answer.exact_match", 1,
                        "{\"outputPath\":\"answer\",\"expectedPath\":\"missing\"}")));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("planVersionId", secondPlan);
        body.put("triggerType", "REEVALUATION");
        body.putArray("runIds").add(run.runId());
        String idemKey = idem("reeval");
        ResponseEntity<String> created = post("/api/v1/experiments/" + run.experimentId()
                + "/evaluation-batches", idemKey, body.toString());
        assertThat(created.getStatusCode().value()).as("%s", created.getBody()).isEqualTo(202);
        String newBatchId = dataId(created);
        assertThat(data(created).path("triggerType").asText()).isEqualTo("REEVALUATION");
        assertThat(data(created).path("batchNo").asInt()).isEqualTo(2);
        assertThat(data(created).path("expectedJobCount").asLong()).isEqualTo(1);

        // 幂等键重放：同一批次，不产生第二套 Job
        ResponseEntity<String> replay = post("/api/v1/experiments/" + run.experimentId()
                + "/evaluation-batches", idemKey, body.toString());
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(dataId(replay)).isEqualTo(newBatchId);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?",
                run.experimentId())).isEqualTo(2);

        // 重评批次在新方案版本下产生 Job；旧 Job 仍在旧版本上
        String newJobId = jdbc.queryForObject("SELECT id FROM evaluation_jobs WHERE batch_id = ?",
                String.class, newBatchId);
        assertThat(jdbc.queryForObject("SELECT evaluation_plan_version_id FROM evaluation_jobs"
                + " WHERE id = ?", String.class, newJobId)).isEqualTo(secondPlan);
        assertThat(jdbc.queryForObject("SELECT evaluation_plan_version_id FROM evaluation_jobs"
                + " WHERE id = ?", String.class, run.jobId())).isNotEqualTo(secondPlan);

        JsonNode task = single(claimAs(workerId("eval2"), "EVAL:answer.exact_match"));
        completeClaimed(task, "NOT_APPLICABLE", null, "样本缺少 ground truth: missing",
                "[{\"type\":\"casePath\",\"path\":\"expected.missing\"}]",
                "{\"expectedPath\":\"missing\",\"expectedPresent\":false}", "RULE");

        // 历史结果原封不动：同一 id、同一 verdict、同一 Attempt
        assertThat(jdbc.queryForObject("SELECT id FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId())).isEqualTo(originalResultId);
        assertThat(jdbc.queryForObject("SELECT verdict FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId())).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT verdict FROM evaluation_results WHERE job_id = ?",
                String.class, newJobId)).isEqualTo("NOT_APPLICABLE");
        // 重评不会把已完成的历史 Run 改回 EVALUATING 或改成 FAILED
        assertThat(runStatus(run.runId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_batches WHERE id = ?",
                String.class, run.batchId())).isEqualTo("COMPLETED");
        assertThat(evaluationBatchStatus(newBatchId)).isEqualTo("COMPLETED");

        // 一次查询能同时看到两个批次的结果，且按批次号倒序
        JsonNode summary = data(get("/api/v1/runs/" + run.runId() + "/evaluation-jobs"));
        assertThat(summary.path("batches").size()).isEqualTo(2);
        assertThat(summary.path("batches").get(0).path("batchNo").asInt()).isEqualTo(2);
        assertThat(summary.path("jobs").get(0).path("batchNo").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("初始批次不能单独取消；重评批次取消后 Job 置 SKIPPED 且不再被领取")
    void cancelRulesPerBatchType() {
        Running run = preparedOnlineRun();
        ResponseEntity<String> initialCancel = post("/api/v1/evaluation-batches/"
                + run.batchId() + "/cancel", null, "{\"reason\":\"不该允许\"}");
        assertThat(initialCancel.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(initialCancel)).isEqualTo("INVALID_STATE_TRANSITION");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("planVersionId", run.planVersionId());
        body.putArray("runIds").add(run.runId());
        // 先把初始 Job 做完，让 Run 进入 COMPLETED，才有资格重评
        completeAndReadResultId(run);
        ResponseEntity<String> created = post("/api/v1/experiments/" + run.experimentId()
                + "/evaluation-batches", idem("reeval-cancel"), body.toString());
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        String newBatchId = dataId(created);

        ResponseEntity<String> cancelled = post("/api/v1/evaluation-batches/" + newBatchId + "/cancel",
                null, "{\"reason\":\"测试取消\"}");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data(cancelled).path("jobs").path("skipped").asLong()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE batch_id = ? AND status = 'SKIPPED'",
                newBatchId)).isEqualTo(1);
        assertThat(claimAs(workerId("eval"), "EVAL:output.non_empty"))
                .as("已取消批次不能再发出任务").isEmpty();
    }

    @Test
    @DisplayName("重评只接受已封口的 Run：未封口或跨实验一律拒绝")
    void reevaluationRejectsUnsealedAndForeignRuns() {
        // 先消费掉自己的 Run，避免后面准备的 QUEUED Run 被这次领取顺手带走
        Running other = preparedOnlineRun();

        Scenario scenario = scenarioWith(publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}"))), 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        String pendingRunId = runIdOf(experimentId, "QUEUED");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("planVersionId", scenario.planVersionId());
        body.putArray("runIds").add(pendingRunId);
        ResponseEntity<String> unsealed = post("/api/v1/experiments/" + experimentId
                + "/evaluation-batches", idem("reeval-unsealed"), body.toString());
        assertThat(unsealed.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(unsealed)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(unsealed.getBody()).contains("只有已封口的 Run 才能重评");

        // 跨实验：Run 属于另一个实验，不能塞进这个实验的批次
        ObjectNode cross = objectMapper.createObjectNode();
        cross.put("planVersionId", other.planVersionId());
        cross.putArray("runIds").add(pendingRunId);
        ResponseEntity<String> foreign = post("/api/v1/experiments/" + other.experimentId()
                + "/evaluation-batches", idem("reeval-cross"), cross.toString());
        assertThat(foreign.getStatusCode().value()).isEqualTo(400);
        assertThat(foreign.getBody()).contains("不属于该实验");
        assertThat(count("SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?",
                other.experimentId())).as("只有初始批次，没有被污染的新批次").isEqualTo(1);
    }

    // ================================================================== ⑨ 在线/离线

    @Test
    @DisplayName("离线 Run 走同一评测入口：无 Attempt、按 Run 读轨迹、结果同样落库")
    void offlineRunUsesTheSameEngine() {
        Scenario scenario = scenarioWith(publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}"))), 2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                runLine("historical_a", "case-2", 1, "record-002", "{\"answer\":\"\"}")));
        String importBatchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                artifactId, idem("import")));
        process(importBatchId);
        start(experimentId, idem("start"));

        String batchId = jdbc.queryForObject("SELECT id FROM evaluation_batches WHERE experiment_id = ?"
                + " AND trigger_type = 'INITIAL'", String.class, experimentId);
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE batch_id = ?", batchId)).isEqualTo(2);

        List<JsonNode> tasks = claimAs(workerId("eval"), "EVAL:output.non_empty");
        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.path("kind").asText()).isEqualTo("EVALUATION");
            JsonNode snapshot = task.path("input").path("acceptedRunSnapshot");
            assertThat(snapshot.path("mode").asText()).isEqualTo("OFFLINE");
            // 离线 Run 没有执行 Attempt；NON_NULL 序列化下该字段整体缺省
            assertThat(snapshot.path("acceptedAttemptId").isMissingNode()
                    || snapshot.path("acceptedAttemptId").isNull()).isTrue();
            assertThat(snapshot.path("trace").path("sealed").asBoolean()).isTrue();
            // 离线轨迹来自导入，按 run_id 读取
            assertThat(snapshot.path("trace").path("eventsUrl").asText()).doesNotContain("attemptId=");
            assertThat(snapshot.path("trace").path("eventCount").asInt()).isEqualTo(1);
        });

        // 一条有输出、一条输出为空：同一引擎给出两个不同结论
        completeClaimed(tasks.get(0), "PASS", new BigDecimal("1.0"), "输出非空", "[]",
                "{\"empty\":false}", "RULE");
        completeClaimed(tasks.get(1), "FAIL", new BigDecimal("0.0"), "输出为空", "[]",
                "{\"empty\":true}", "RULE");

        assertThat(evaluationBatchStatus(batchId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE batch_id = ? AND status = 'COMPLETED'",
                batchId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_results r JOIN evaluation_jobs j"
                + " ON j.id = r.job_id WHERE j.batch_id = ?", batchId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND status = 'COMPLETED'", experimentId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM run_attempts a JOIN evaluation_runs r"
                + " ON r.id = a.run_id WHERE r.experiment_id = ?", experimentId))
                .as("离线 Run 不产生执行 Attempt").isZero();
    }

    @Test
    @DisplayName("业务失败的 Run 仍进入评测，缺失输出报不适用")
    void businessFailureIsStillEvaluated() {
        Scenario scenario = scenarioWith(publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}"))), 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode runTask = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = runTask.path("runId").asText();
        List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                runTask.path("leaseToken").asText(), sampleEvents());
        // 业务失败、没有输出
        ObjectNode body = objectMapper.createObjectNode();
        body.put("leaseToken", runTask.path("leaseToken").asText());
        body.put("taskOutcome", "FAILURE");
        body.put("usage", objectMapper.createObjectNode());
        body.put("expectedEventCount", eventIds.size());
        body.put("eventManifestSha256", manifest(eventIds));
        ResponseEntity<String> completed = workerPost("/internal/v1/run-attempts/"
                + runTask.path("attemptId").asText() + "/complete", body);
        assertThat(completed.getStatusCode().value()).as("%s", completed.getBody()).isEqualTo(200);

        assertThat(runStatus(runId)).isEqualTo("EVALUATING");
        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        assertThat(task.path("input").path("acceptedRunSnapshot").path("taskOutcome").asText())
                .isEqualTo("FAILURE");
        assertThat(isNullOrMissing(task.path("input").path("acceptedRunSnapshot").get("output"))).isTrue();
        completeClaimed(task, "NOT_APPLICABLE", null, "来源缺失输出，无法判断是否为空", "[]",
                "{\"outputPath\":\"answer\",\"outputPresent\":false}", "RULE");

        assertThat(jobStatus(singleJobId(runId))).isEqualTo("COMPLETED");
        // 评测完成不等于执行成功：Run 的 task_outcome 仍然记录业务失败
        assertThat(jdbc.queryForObject("SELECT task_outcome FROM evaluation_runs WHERE id = ?",
                String.class, runId)).isEqualTo("FAILURE");
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(runStatus(runId)).isNotEqualTo("FAILED");
    }

    // ================================================================== 辅助

    /** 一个可用的在线 Run：已封口、Job 已生成、等待领取。 */
    private record Running(String experimentId, String runId, String jobId, String batchId,
                           String planVersionId) {
    }

    private Running preparedOnlineRun() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        JsonNode task = single(claimAs(workerId("runner"), "RUN:MOCK"));
        String runId = task.path("runId").asText();
        List<String> eventIds = appendEvents(task.path("attemptId").asText(),
                task.path("leaseToken").asText(), sampleEvents());
        assertThat(completeRun(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                "SUCCESS", "{\"answer\":\"42\"}", eventIds).getStatusCode().value()).isEqualTo(200);
        String jobId = singleJobId(runId);
        return new Running(experimentId, runId, jobId, batchIdOf(jobId), planVersionId);
    }

    private String completeAndReadResultId(Running run) {
        JsonNode task = single(claimAs(workerId("eval"), "EVAL:output.non_empty"));
        completeClaimed(task, "PASS", new BigDecimal("1.0"), "输出非空", "[]",
                "{\"empty\":false}", "RULE");
        return jdbc.queryForObject("SELECT id FROM evaluation_results WHERE job_id = ?",
                String.class, run.jobId());
    }

    /** 评测批次状态：走新的批次详情接口，不复用基类查导入批次的 batchStatus。 */
    private String evaluationBatchStatus(String batchId) {
        return data(get("/api/v1/evaluation-batches/" + batchId)).path("status").asText();
    }

}
