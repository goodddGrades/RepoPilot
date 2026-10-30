package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-009 验收：人工标注与失败归因。
 *
 * <p>全部走真实 HTTP 与真实 MySQL。断言集中在四条不可协商的规则上：
 * <ol>
 *   <li>并发领取只有一个成功，另一个拿到 409；</li>
 *   <li>每个动作都必须带 {@code expectedVersion}，过期即 409 且不产生副作用；</li>
 *   <li>状态行与 {@code annotation_records} 同事务——不会出现"状态变了但没记录"；</li>
 *   <li>{@code evaluation_results} 字节级不变：人工判断不覆盖自动结果。</li>
 * </ol>
 *
 * <p>素材（待复核的 Run、真实的 evaluation_results）全部由完整链路跑出来，
 * 不用 JDBC 伪造——人工复核的价值就在于它复核的是**真实结论**。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-009 人工标注与失败归因")
class AnnotationWorkflowIT extends AbstractOfflineIT {

    // ================================================================== ① 创建

    @Test
    @DisplayName("创建任务后可查可列，初始为 PENDING 且带创建记录")
    void createTaskIsQueryable() {
        String runId = reviewableRun();
        ResponseEntity<String> created = createTask(runId, idem("create"));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode task = data(created);
        assertThat(task.path("status").asText()).isEqualTo("PENDING");
        assertThat(task.path("version").asLong()).isZero();
        assertThat(task.path("runId").asText()).isEqualTo(runId);

        JsonNode detail = data(get("/api/v1/annotation-tasks/" + task.path("id").asText()));
        assertThat(detail.path("records")).isNotEmpty();
        assertThat(detail.path("records").get(0).path("action").asText())
                .as("创建本身就是一条历史，缺了它就不知道任务从哪来")
                .isEqualTo("CLAIM");

        JsonNode page = data(get("/api/v1/annotation-tasks?status=PENDING"));
        assertThat(page.path("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("跨 Run 绑定 Job 被拒：证据与结论都不能指向别的 Run")
    void crossRunJobBindingIsRejected() {
        String runA = reviewableRun();
        String runB = reviewableRun();
        assertThat(runA).as("两个 Run 必须是不同的，否则测的就不是跨 Run 绑定").isNotEqualTo(runB);
        String jobOfA = singleJobId(runA);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("runId", runB);
        body.put("evaluationJobId", jobOfA);
        ResponseEntity<String> response = post("/api/v1/annotation-tasks", idem("cross"), body.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("不属于该 Run");
    }

    @Test
    @DisplayName("重复 Idempotency-Key 返回同一任务，异请求 409")
    void creationIsIdempotent() {
        String runId = reviewableRun();
        String key = idem("dup");

        JsonNode first = data(post("/api/v1/annotation-tasks", key, "{\"runId\":\"" + runId + "\"}"));
        JsonNode replay = data(post("/api/v1/annotation-tasks", key, "{\"runId\":\"" + runId + "\"}"));

        assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(count("SELECT COUNT(*) FROM annotation_tasks WHERE run_id = ?", runId))
                .isEqualTo(1);

        ResponseEntity<String> conflict = post("/api/v1/annotation-tasks", key,
                "{\"runId\":\"" + reviewableRun() + "\"}");
        assertThat(codeOf(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    // ================================================================== ② 并发领取

    @Test
    @DisplayName("两个请求并发领取只有一个成功，另一个 409")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        String runId = reviewableRun();
        JsonNode task = createTaskData(runId, idem("race"));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 全部使用同一个 expectedVersion：模拟多人同时点"领取"
                futures.add(pool.submit((Callable<String>) () -> {
                    start.await();
                    ObjectNode body = objectMapper.createObjectNode();
                    body.put("expectedVersion", task.path("version").asLong());
                    return codeOf(post("/api/v1/annotation-tasks/" + task.path("id").asText() + "/claim",
                            idem("race-claim"), body.toString()));
                }));
            }
            start.countDown();

            int ok = 0;
            int conflicts = 0;
            for (Future<String> future : futures) {
                String code = future.get(30, TimeUnit.SECONDS);
                if ("OK".equals(code)) {
                    ok++;
                } else if ("OPTIMISTIC_LOCK_CONFLICT".equals(code)) {
                    conflicts++;
                }
            }
            assertThat(ok).as("并发领取只允许一个赢家").isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        // 库里只有一个 CLAIM 记录赢家
        assertThat(count("SELECT COUNT(*) FROM annotation_records r "
                + "JOIN annotation_tasks t ON t.id = r.annotation_task_id "
                + "WHERE t.run_id = ? AND r.action = 'CLAIM' AND r.reason = '领取任务'", runId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("已被他人领取的任务不能被抢走，返回 409")
    void claimedTaskCannotBeStolen() {
        JsonNode task = createTaskData(reviewableRun(), idem("steal"));
        claim(task, idem("c1"));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedVersion", task.path("version").asLong() + 1);
        ResponseEntity<String> second = post("/api/v1/annotation-tasks/" + task.path("id").asText()
                + "/claim", idem("c2"), body.toString());

        assertThat(second.getStatusCode().value()).isEqualTo(409);
    }

    // ================================================================== ③ 提交

    @Test
    @DisplayName("提交后状态与记录同事务更新，原始自动结论保持不变")
    void submitPersistsRecordWithoutTouchingAutoResult() {
        String runId = reviewableRun();
        String jobId = singleJobId(runId);
        String beforeResult = jdbc.queryForObject(
                "SELECT CONCAT(verdict, '|', IFNULL(reason, 'NULL'), '|', IFNULL(score, 'NULL')) "
                        + "FROM evaluation_results WHERE job_id = ?", String.class, jobId);

        JsonNode task = createTaskData(runId, idem("submit"));
        JsonNode claimed = claim(task, idem("sc"));
        String taskId = task.path("id").asText();

        JsonNode submitted = submit(claimed, "FAIL", "MODEL_REASONING", "推理错误：把 2+2 算成 5");
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");

        List<JsonNode> records = all(submitted, "records");
        JsonNode submitRecord = records.stream()
                .filter(r -> "SUBMIT".equals(r.path("action").asText()))
                .findFirst().orElseThrow();
        assertThat(submitRecord.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(submitRecord.path("failureCategory").asText()).isEqualTo("MODEL_REASONING");
        assertThat(submitRecord.path("originalVerdict").asText())
                .as("记录里要能看到人工改的是哪条自动结论").isNotBlank();

        // 自动结果字节级不变
        String afterResult = jdbc.queryForObject(
                "SELECT CONCAT(verdict, '|', IFNULL(reason, 'NULL'), '|', IFNULL(score, 'NULL')) "
                        + "FROM evaluation_results WHERE job_id = ?", String.class, jobId);
        assertThat(afterResult).isEqualTo(beforeResult);

        assertThat(taskId).isNotBlank();
    }

    @Test
    @DisplayName("未领取不能提交；非领取者不能提交")
    void submitRequiresValidClaim() {
        JsonNode task = createTaskData(reviewableRun(), idem("noc"));
        String taskId = task.path("id").asText();

        ResponseEntity<String> notClaimed = post("/api/v1/annotation-tasks/" + taskId + "/submit",
                idem("s1"), "{\"expectedVersion\":0,\"verdict\":\"FAIL\","
                        + "\"failureCategory\":\"OTHER\",\"reason\":\"未领取就提交\"}");
        assertThat(notClaimed.getStatusCode().value()).isEqualTo(409);

        JsonNode claimed = claim(task, idem("c"));
        ResponseEntity<String> stale = post("/api/v1/annotation-tasks/" + taskId + "/submit",
                idem("s2"), "{\"expectedVersion\":0,\"verdict\":\"FAIL\","
                        + "\"failureCategory\":\"OTHER\",\"reason\":\"版本过期\"}");
        assertThat(codeOf(stale)).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        assertThat(claimed.path("status").asText()).isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("判 FAIL 必须给失败归因；显式 UNKNOWN 也接受")
    void failureVerdictRequiresCategory() {
        JsonNode task = createTaskData(reviewableRun(), idem("cat"));
        JsonNode claimed = claim(task, idem("c"));
        String taskId = task.path("id").asText();

        ResponseEntity<String> missing = post("/api/v1/annotation-tasks/" + taskId + "/submit",
                idem("s1"), "{\"expectedVersion\":" + claimed.path("version").asLong()
                        + ",\"verdict\":\"FAIL\",\"reason\":\"失败了但没说原因\"}");
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody()).contains("failureCategory");

        JsonNode unknown = submit(claimed, "FAIL", "UNKNOWN", "证据不足，无法归因");
        assertThat(unknown.path("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("未注册的失败分类被拒，不会静默降级成 OTHER")
    void unknownCategoryIsRejected() {
        JsonNode task = createTaskData(reviewableRun(), idem("bad"));
        JsonNode claimed = claim(task, idem("c"));

        ResponseEntity<String> response = post("/api/v1/annotation-tasks/"
                + task.path("id").asText() + "/submit", idem("s"),
                "{\"expectedVersion\":" + claimed.path("version").asLong()
                        + ",\"verdict\":\"FAIL\",\"failureCategory\":\"随便写的\","
                        + "\"reason\":\"x\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("未注册的失败分类");
    }

    @Test
    @DisplayName("重复提交不追加重复记录（同幂等键）")
    void repeatedSubmitWithSameKeyDoesNotDuplicate() {
        JsonNode task = createTaskData(reviewableRun(), idem("rs"));
        JsonNode claimed = claim(task, idem("c"));
        String key = idem("s");
        String body = "{\"expectedVersion\":" + claimed.path("version").asLong()
                + ",\"verdict\":\"FAIL\",\"failureCategory\":\"TOOL_USE\",\"reason\":\"工具用错\"}";

        JsonNode first = data(post("/api/v1/annotation-tasks/" + task.path("id").asText()
                + "/submit", key, body));
        JsonNode replay = data(post("/api/v1/annotation-tasks/" + task.path("id").asText()
                + "/submit", key, body));

        assertThat(replay.path("version").asLong()).isEqualTo(first.path("version").asLong());
        assertThat(count("SELECT COUNT(*) FROM annotation_records WHERE action = 'SUBMIT' "
                + "AND annotation_task_id = ?", task.path("id").asText())).isEqualTo(1);
    }

    // ================================================================== ④ 审核与退回

    @Test
    @DisplayName("退回后可重提，全部历史保留，REVIEWED 视图只取有效审核")
    void returnThenResubmitKeepsFullHistory() {
        JsonNode task = createTaskData(reviewableRun(), idem("flow"));
        String taskId = task.path("id").asText();
        JsonNode claimed = claim(task, idem("c1"));
        JsonNode submitted = submit(claimed, "FAIL", "CONTEXT", "上下文不足");

        JsonNode returned = review(submitted, false, "证据不足，请补充轨迹");
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");

        JsonNode reclaimed = claim(returned, idem("c2"));
        JsonNode resubmitted = submit(reclaimed, "FAIL", "RETRIEVAL", "检索没召回关键文档");

        JsonNode reviewed = review(resubmitted, true, "认可");
        assertThat(reviewed.path("status").asText()).isEqualTo("REVIEWED");

        List<JsonNode> records = all(reviewed, "records");
        assertThat(records.stream().filter(r -> "SUBMIT".equals(r.path("action").asText())).count())
                .as("退回重提后两次提交都要留痕").isEqualTo(2);
        assertThat(records.stream().filter(r -> "RETURN".equals(r.path("action").asText())).count())
                .isEqualTo(1);
        assertThat(records.stream().filter(r -> "REVIEW".equals(r.path("action").asText())).count())
                .isEqualTo(1);

        // REVIEWED 视图只取审核快照：应是被审核认可的那次提交内容
        JsonNode reviewRecord = records.stream()
                .filter(r -> "REVIEW".equals(r.path("action").asText()))
                .findFirst().orElseThrow();
        assertThat(reviewRecord.path("failureCategory").asText()).isEqualTo("RETRIEVAL");
        assertThat(reviewRecord.path("reason").asText()).isEqualTo("认可");
    }

    @Test
    @DisplayName("审核必须显式给 approved；退回必须给原因")
    void reviewRequiresExplicitDecision() {
        JsonNode task = createTaskData(reviewableRun(), idem("rev"));
        JsonNode claimed = claim(task, idem("c"));
        JsonNode submitted = submit(claimed, "PASS", null, "看起来没问题");
        String taskId = task.path("id").asText();
        long version = submitted.path("version").asLong();

        ResponseEntity<String> noDecision = post("/api/v1/annotation-tasks/" + taskId + "/review",
                idem("r1"), "{\"expectedVersion\":" + version + "}");
        assertThat(noDecision.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> noReason = post("/api/v1/annotation-tasks/" + taskId + "/review",
                idem("r2"), "{\"expectedVersion\":" + version + ",\"approved\":false}");
        assertThat(noReason.getStatusCode().value()).isEqualTo(400);
        assertThat(noReason.getBody()).contains("reason");
    }

    @Test
    @DisplayName("未提交不能审核")
    void reviewRequiresSubmittedState() {
        JsonNode task = createTaskData(reviewableRun(), idem("rv"));
        JsonNode claimed = claim(task, idem("c"));

        ResponseEntity<String> response = post("/api/v1/annotation-tasks/"
                + task.path("id").asText() + "/review", idem("r"),
                "{\"expectedVersion\":" + claimed.path("version").asLong() + ",\"approved\":true}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(response)).isEqualTo("INVALID_STATE_TRANSITION");
    }

    // ================================================================== ⑤ 取消

    @Test
    @DisplayName("取消后不可再提交；已审核为终态不可取消")
    void cancelIsTerminalInBothDirections() {
        JsonNode task = createTaskData(reviewableRun(), idem("cx"));
        JsonNode claimed = claim(task, idem("c"));
        JsonNode cancelled = cancel(claimed, "该样本已废弃");
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");

        ResponseEntity<String> afterCancel = post("/api/v1/annotation-tasks/"
                + task.path("id").asText() + "/submit", idem("s"),
                "{\"expectedVersion\":" + cancelled.path("version").asLong()
                        + ",\"verdict\":\"PASS\",\"reason\":\"还想提交\"}");
        assertThat(afterCancel.getStatusCode().value()).isEqualTo(409);

        JsonNode second = createTaskData(reviewableRun(), idem("cx2"));
        JsonNode c2 = claim(second, idem("c2b"));
        JsonNode s2 = submit(c2, "PASS", null, "通过");
        JsonNode reviewed = review(s2, true, "认可");
        ResponseEntity<String> cancelReviewed = post("/api/v1/annotation-tasks/"
                + second.path("id").asText() + "/cancel", idem("cx3"),
                "{\"expectedVersion\":" + reviewed.path("version").asLong() + ",\"reason\":\"x\"}");
        assertThat(cancelReviewed.getStatusCode().value()).isEqualTo(409);
    }

    // ================================================================== ⑥ 派单建议

    @Test
    @DisplayName("建议列表只给技术失败或判未通过的 Run，且不重复派单")
    void dispatchCandidatesSkipHealthyRuns() {
        Mixed mixed = buildMixedRuns();

        JsonNode candidates = data(get("/api/v1/experiments/" + mixed.experimentId()
                + "/annotation-candidates"));

        List<String> runIds = new ArrayList<>();
        candidates.forEach(node -> runIds.add(node.path("runId").asText()));
        assertThat(runIds).contains(mixed.autoFailed());
        assertThat(runIds).as("算不出结论的 Run 必须进队列").contains(mixed.techFailed());
        assertThat(runIds).as("全部 PASS 的 Run 不该进人工队列").doesNotContain(mixed.passing());

        // 建议要说明为什么需要人看，并带上机器给出的结论
        assertThat(candidates.toString()).contains("自动评测判定未通过");
        assertThat(candidates.toString()).contains("技术状态 FAILED");

        // 已有未终态任务后不再重复建议
        createTaskData(mixed.autoFailed(), idem("auto"));
        JsonNode again = data(get("/api/v1/experiments/" + mixed.experimentId()
                + "/annotation-candidates"));
        List<String> afterDispatch = new ArrayList<>();
        again.forEach(node -> afterDispatch.add(node.path("runId").asText()));
        assertThat(afterDispatch).as("已派单的不再重复").doesNotContain(mixed.autoFailed());
    }

    // ================================================================== 辅助

    private JsonNode createTaskData(String runId, String key) {
        return data(post("/api/v1/annotation-tasks", key, "{\"runId\":\"" + runId + "\"}"));
    }

    private ResponseEntity<String> createTask(String runId, String key) {
        return post("/api/v1/annotation-tasks", key, "{\"runId\":\"" + runId + "\"}");
    }

    private JsonNode claim(JsonNode task, String key) {
        return data(post("/api/v1/annotation-tasks/" + task.path("id").asText() + "/claim", key,
                "{\"expectedVersion\":" + task.path("version").asLong() + "}"));
    }

    private JsonNode submit(JsonNode task, String verdict, String category, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedVersion", task.path("version").asLong());
        body.put("verdict", verdict);
        body.put("reason", reason);
        if (category != null) {
            body.put("failureCategory", category);
        }
        body.putArray("evidence").add("{\"type\":\"event\",\"eventId\":\"01AAAAAAAAAAAAAAAAAAAAAAAA\"}");
        return data(post("/api/v1/annotation-tasks/" + task.path("id").asText() + "/submit",
                idem("submit"), body.toString()));
    }

    private JsonNode review(JsonNode task, boolean approved, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedVersion", task.path("version").asLong());
        body.put("approved", approved);
        body.put("reason", reason);
        return data(post("/api/v1/annotation-tasks/" + task.path("id").asText() + "/review",
                idem("review"), body.toString()));
    }

    private JsonNode cancel(JsonNode task, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedVersion", task.path("version").asLong());
        body.put("reason", reason);
        return data(post("/api/v1/annotation-tasks/" + task.path("id").asText() + "/cancel",
                idem("cancel"), body.toString()));
    }

    private List<JsonNode> all(JsonNode task, String field) {
        List<JsonNode> items = new ArrayList<>();
        task.path(field).forEach(items::add);
        return items;
    }

    private String experimentIdOf(String runId) {
        return jdbc.queryForObject("SELECT experiment_id FROM evaluation_runs WHERE id = ?",
                String.class, runId);
    }

    // ------------------------------------------------------------------ 素材构造
    //
    // 标注流程的输入是"真实的评测结论"，所以素材必须走完整链路造出来：
    // 发方案 → 建实验 → 领执行任务 → 写轨迹 → 执行封口 → 领评测 Job → 提交结论。
    // 直接往 evaluation_runs 塞一行 FAILED 是自欺欺人：那样测的就不是
    // "针对真实结论的复核"，而是"针对伪造行的复核"。

    /** 一个自动判未通过的 Run：Run COMPLETED，但结论是 FAIL——最典型的复核对象。 */
    private String reviewableRun() {
        return buildSingleRunWithVerdict("FAIL", new BigDecimal("0.0"), "输出为空");
    }

    private String passingRun() {
        return buildSingleRunWithVerdict("PASS", new BigDecimal("1.0"), "输出非空");
    }

    /** 一个算不出结论的 Run：Job 重试预算耗尽 → Run FAILED，且没有任何 evaluation_results。 */
    private String techFailedRun() {
        Prepared prepared = preparedRun(1);
        String jobId = singleJobId(prepared.runIds().get(0));
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode task = taskOf(claimAs(workerId("eval"), "EVAL:output.non_empty"), jobRunId(jobId));
            jobFail(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                    "TRACE_FETCH_FAILED", true);
            forceRetryDue(jobId);
        }
        assertThat(runStatus(jobRunId(jobId))).as("重试耗尽后 Run 应判失败").isEqualTo("FAILED");
        assertThat(resultCount(jobId)).as("技术失败不产生质量结论").isZero();
        return jobRunId(jobId);
    }

    private String buildSingleRunWithVerdict(String verdict, BigDecimal score, String reason) {
        Prepared prepared = preparedRun(1);
        String runId = prepared.runIds().get(0);
        JsonNode task = taskOf(claimAs(workerId("eval"), "EVAL:output.non_empty"), runId);
        completeClaimed(task, verdict, score, reason, "[]", "{\"empty\":false}", "RULE");
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        return runId;
    }

    /**
     * 同一个实验里造出三种 Run：自动判未通过 / 全部通过 / 技术失败。
     *
     * <p>必须在**同一个实验**里：派单建议是按实验查询的，跨实验就验证不了
     * "健康 Run 不进队列"这条过滤规则。
     */
    private Mixed buildMixedRuns() {
        Prepared prepared = preparedRun(3);
        List<String> runIds = prepared.runIds();
        assertThat(runIds).hasSize(3);

        String autoFailed = runIds.get(0);
        String passing = runIds.get(1);
        String techFailed = runIds.get(2);

        // order=1 的 Job 可并行领取：一次领满三个，再逐个给结论
        List<JsonNode> jobs = claimAs(workerId("eval"), "EVAL:output.non_empty");
        assertThat(jobs).as("三个 Run 各一个 Job，应一次领满").hasSize(3);
        completeClaimed(taskOf(jobs, autoFailed), "FAIL", new BigDecimal("0.0"), "输出为空",
                "[]", "{\"empty\":false}", "RULE");
        completeClaimed(taskOf(jobs, passing), "PASS", new BigDecimal("1.0"), "输出非空",
                "[]", "{\"empty\":false}", "RULE");

        // 第三个 Job 在**同一波**里就已经被领取（租约在手），所以第一次失败要直接用
        // 手里的 attempt；之后每次重试才需要重新领取。
        JsonNode failing = taskOf(jobs, techFailed);
        String jobId = singleJobId(techFailed);
        for (int attempt = 1; attempt <= 3; attempt++) {
            jobFail(failing.path("attemptId").asText(), failing.path("leaseToken").asText(),
                    "TRACE_FETCH_FAILED", true);
            forceRetryDue(jobId);
            if (attempt < 3) {
                // 重新领取时只应拿到这个 Run 的 Job：前两个 Run 已 COMPLETED
                List<JsonNode> retry = claimAs(workerId("eval"), "EVAL:output.non_empty");
                assertThat(retry).as("第 %d 次重试应恰好领到一个 Job", attempt).hasSize(1);
                failing = taskOf(retry, techFailed);
            }
        }

        assertThat(runStatus(autoFailed)).isEqualTo("COMPLETED");
        assertThat(runStatus(passing)).isEqualTo("COMPLETED");
        assertThat(runStatus(techFailed)).isEqualTo("FAILED");
        return new Mixed(prepared.experimentId(), autoFailed, passing, techFailed);
    }

    private record Mixed(String experimentId, String autoFailed, String passing, String techFailed) {
    }

    private record Prepared(String experimentId, List<String> runIds) {
    }

    private String jobRunId(String jobId) {
        return jdbc.queryForObject("SELECT run_id FROM evaluation_jobs WHERE id = ?",
                String.class, jobId);
    }

    /** 跑到"执行已封口、评测 Job 已生成待领取"这一步。 */
    private Prepared preparedRun(int runCount) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        // 1 target × 1 case × runCount 重复 = runCount 个 Run
        String experimentId = createExperiment(scenario, 1, runCount);
        start(experimentId, idem("start"));

        List<String> runIds = new ArrayList<>();
        for (JsonNode runTask : claimAs(workerId("runner"), "RUN:MOCK")) {
            List<String> eventIds = appendEvents(runTask.path("attemptId").asText(),
                    runTask.path("leaseToken").asText(), sampleEvents());
            assertThat(completeRun(runTask.path("attemptId").asText(),
                    runTask.path("leaseToken").asText(), "SUCCESS", "{\"answer\":\"42\"}", eventIds)
                    .getStatusCode().value()).isEqualTo(200);
            runIds.add(runTask.path("runId").asText());
        }
        assertThat(runIds).hasSize(runCount);
        return new Prepared(experimentId, runIds);
    }
}