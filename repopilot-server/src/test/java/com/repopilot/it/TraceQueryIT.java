package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.modules.experiment.service.WorkService;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-006 轨迹查询验收：真实 HTTP + 真实 MySQL。
 *
 * <p>覆盖在线与离线两类 Run、同毫秒平局、多页拼接、游标篡改/跨过滤/跨 Run 复用、
 * 非法参数、空轨迹、失败 Attempt 隔离与大量事件下的边界。
 * 每条用例都同时断言响应结构、数据库事实与分页不变式，不以 200 作为成功标准。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-006 统一 Trace 查询")
class TraceQueryIT extends AbstractOfflineIT {

    @Autowired
    private WorkService workService;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // ------------------------------------------------------------------ 在线

    private record OnlineRun(String experimentId, String runId, String attemptId, String token,
                             List<String> eventIds) {
    }

    /** 跑完一次在线执行：领取 → 写事件（同一毫秒，专门用于验证平局排序）→ 封口。 */
    private OnlineRun runOnline(Scenario scenario, String workerId) {
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());
        JsonNode task = claim(workerId, 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        List<String> ids = appendEvents(attemptId, token, sampleEvents());
        ResponseEntity<String> completed = complete(attemptId, token, "SUCCESS",
                objectMapper.createObjectNode().put("answer", "42"), ids);
        assertThat(completed.getStatusCode().value())
                .as("封口应成功: %s", completed.getBody()).isEqualTo(200);
        return new OnlineRun(experimentId, task.path("runId").asText(), attemptId, token, ids);
    }

    /** 用公共上传接口为某个 Run 建一个附件（模拟执行产出）。 */
    private String uploadArtifactForRun(String runId, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("runId", runId);
        form.add("artifactType", "OUTPUT");
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "evidence.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/artifacts", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().value())
                .as("附件上传应返回 201: %s", response.getBody()).isEqualTo(201);
        return dataId(response);
    }

    private JsonNode eventPage(String runId, String query) {
        return data(get("/api/v1/runs/" + runId + "/events" + query));
    }

    private ResponseEntity<String> eventPageRaw(String runId, String query) {
        return get("/api/v1/runs/" + runId + "/events" + query);
    }

    /** 第 i 条事件的发生时间：每条间隔 1 秒，便于验证"从游标处起读"的查询计划。 */
    private static String occurredAt(int index) {
        return java.time.Instant.parse("2026-10-18T00:00:00Z").plusSeconds(index).toString();
    }

    private List<String> eventIdsOf(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("eventId").asText()));
        return ids;
    }

    private List<String> dbEventIds(String runId, String attemptId) {
        return jdbc.queryForList("SELECT event_id FROM trace_events WHERE run_id = ?"
                + (attemptId == null ? "" : " AND run_attempt_id = ?")
                + " ORDER BY occurred_at ASC, id ASC", String.class,
                attemptId == null ? new Object[]{runId} : new Object[]{runId, attemptId});
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("在线 Run 默认读取 accepted Attempt，同毫秒事件按 id 稳定排序")
    void onlineRunShouldReadAcceptedAttemptInStableOrder() {
        Scenario scenario = seedPublished(1);
        OnlineRun run = runOnline(scenario, "it-trace-" + scenario.suffix());

        JsonNode page = eventPage(run.runId(), "?limit=100");
        assertThat(page.path("items")).hasSize(5);
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        // 没有下一页时不得返回游标：前端据此判断"到底了"
        assertThat(page.path("nextCursor").isMissingNode() || page.path("nextCursor").isNull()).isTrue();

        JsonNode scope = page.path("scope");
        assertThat(scope.path("resolvedFrom").asText()).isEqualTo("ACCEPTED");
        assertThat(scope.path("attemptId").asText()).isEqualTo(run.attemptId());
        assertThat(scope.path("allAttempts").asBoolean()).isFalse();

        // 五条事件 occurredAt 完全相同：顺序必须等于数据库的 (occurred_at, id)
        assertThat(eventIdsOf(page)).containsExactlyElementsOf(dbEventIds(run.runId(), run.attemptId()));
        assertThat(page.path("items")).allSatisfy(item ->
                assertThat(item.path("attemptId").asText()).isEqualTo(run.attemptId()));

        JsonNode modelRequest = page.path("items").get(1);
        assertThat(modelRequest.path("eventType").asText()).isEqualTo("MODEL_REQUEST");
        assertThat(modelRequest.path("sequenceNo").asLong()).isEqualTo(2);
        assertThat(modelRequest.path("payload").path("model").asText()).isEqualTo("mock-model");
        // 没有 traceId 的事件不伪造 OTel 标识（NON_NULL 序列化下字段缺省）
        assertThat(modelRequest.hasNonNull("traceId")).isFalse();
    }

    @Test
    @DisplayName("游标翻页不重复不遗漏，拼起来等于一次取全")
    void pagingShouldNotRepeatOrSkip() {
        Scenario scenario = seedPublished(1);
        OnlineRun run = runOnline(scenario, "it-trace-page-" + scenario.suffix());

        List<String> full = eventIdsOf(eventPage(run.runId(), "?limit=100"));
        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (pages < 10) {
            String query = "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = eventPage(run.runId(), query);
            collected.addAll(eventIdsOf(page));
            pages++;
            if (!page.path("hasMore").asBoolean()) {
                break;
            }
            cursor = page.path("nextCursor").asText();
            assertThat(cursor).as("hasMore=true 时必须给出 nextCursor").isNotBlank();
        }

        assertThat(pages).isEqualTo(3);
        assertThat(collected).containsExactlyElementsOf(full);
        assertThat(new LinkedHashSet<>(collected)).hasSize(full.size());
    }

    @Test
    @DisplayName("篡改或截断的游标一律 400，且错误指向 cursor 字段")
    void tamperedCursorShouldBeRejected() {
        Scenario scenario = seedPublished(1);
        OnlineRun run = runOnline(scenario, "it-trace-tamper-" + scenario.suffix());
        String cursor = eventPage(run.runId(), "?limit=1").path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        // 改动正文里的一个字符（保持 base64url 形态），签名必须失配
        char[] chars = cursor.toCharArray();
        int index = chars.length / 3;
        chars[index] = chars[index] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        ResponseEntity<String> response = eventPageRaw(run.runId(), "?limit=1&cursor=" + tampered);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(response).path("field").asText()).isEqualTo("cursor");
        assertThat(count("SELECT COUNT(*) FROM trace_events WHERE run_id = ?", run.runId())).isEqualTo(5);

        // 只有正文、没有签名
        String bodyOnly = cursor.substring(0, cursor.indexOf('.'));
        assertThat(eventPageRaw(run.runId(), "?limit=1&cursor=" + bodyOnly).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("换过滤条件或换 Run 之后旧游标不可复用")
    void cursorShouldNotBeReusableAcrossFiltersOrRuns() {
        Scenario scenario = seedPublished(2);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());
        List<JsonNode> tasks = claim("it-trace-scope-" + scenario.suffix(), 2);
        assertThat(tasks).hasSize(2);

        List<String> runIds = new ArrayList<>();
        List<String> attemptIds = new ArrayList<>();
        for (JsonNode task : tasks) {
            String attemptId = task.path("attemptId").asText();
            String token = task.path("leaseToken").asText();
            List<String> ids = appendEvents(attemptId, token, sampleEvents());
            complete(attemptId, token, "SUCCESS",
                    objectMapper.createObjectNode().put("answer", "42"), ids);
            runIds.add(task.path("runId").asText());
            attemptIds.add(attemptId);
        }

        String cursor = eventPage(runIds.get(0), "?limit=1").path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        // 同一 Run、不同 eventType
        assertThat(eventPageRaw(runIds.get(0), "?limit=1&eventType=TOOL_STARTED&cursor=" + cursor)
                .getStatusCode().value()).isEqualTo(400);
        // 同一 Run、切换成 allAttempts
        assertThat(eventPageRaw(runIds.get(0), "?limit=1&allAttempts=true&cursor=" + cursor)
                .getStatusCode().value()).isEqualTo(400);
        // 同一 Run、显式指定与 accepted 相同的 attemptId：过滤条件等价，游标可继续用
        // （摘要只编码过滤值，不编码"默认解析出来的"过程）
        assertThat(eventPageRaw(runIds.get(0),
                "?limit=1&attemptId=" + attemptIds.get(0) + "&cursor=" + cursor)
                .getStatusCode().value()).isEqualTo(200);
        // 换成另一个 Run
        ResponseEntity<String> crossRun = eventPageRaw(runIds.get(1), "?limit=1&cursor=" + cursor);
        assertThat(crossRun.getStatusCode().value()).isEqualTo(400);
        assertThat(codeOf(crossRun)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(crossRun).path("field").asText()).isEqualTo("cursor");

        // 用正确的条件仍可继续翻页
        assertThat(eventPage(runIds.get(0), "?limit=1&cursor=" + cursor).path("items"))
                .hasSize(1);
    }

    @Test
    @DisplayName("离线 Run 没有 Attempt：不做 Attempt 过滤，也不伪造 Attempt")
    void offlineRunShouldQueryWithoutAttempt() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(
                        runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                        runLine("historical_a", "case-2", 1, "record-002", null))),
                "it-trace-offline-" + scenario.suffix()));
        process(batchId);

        String runId = jdbc.queryForObject("SELECT id FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-001'", String.class, experimentId);

        JsonNode page = eventPage(runId, "");
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("scope").path("resolvedFrom").asText()).isEqualTo("NONE");
        assertThat(page.path("scope").hasNonNull("attemptId")).isFalse();
        // 离线事件没有执行尝试：runAttemptId 必须缺省
        assertThat(page.path("items").get(0).hasNonNull("attemptId")).isFalse();
        assertThat(page.path("items").get(0).path("eventType").asText()).isEqualTo("OUTPUT_PRODUCED");

        // allAttempts 对离线是空操作，不改变结果
        assertThat(eventIdsOf(eventPage(runId, "?allAttempts=true")))
                .containsExactlyElementsOf(eventIdsOf(page));

        // 显式给一个不存在的 Attempt：400，而不是悄悄返回全部事件
        ResponseEntity<String> invalidAttempt = eventPageRaw(runId, "?attemptId=" + Ulid.newId());
        assertThat(invalidAttempt.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(invalidAttempt).path("field").asText()).isEqualTo("attemptId");

        // 离线 Run 的 attempt 表为空：接口没有为了统一而造数据
        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isZero();
    }

    @Test
    @DisplayName("不存在的 Run 返回 404，非法参数返回 400")
    void invalidRequestsShouldFailWithContractErrors() {
        Scenario scenario = seedPublished(1);
        OnlineRun run = runOnline(scenario, "it-trace-invalid-" + scenario.suffix());

        ResponseEntity<String> missing = eventPageRaw(Ulid.newId(), "");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("RESOURCE_NOT_FOUND");

        assertThat(eventPageRaw(run.runId(), "?limit=0").getStatusCode().value()).isEqualTo(400);
        assertThat(eventPageRaw(run.runId(), "?limit=501").getStatusCode().value()).isEqualTo(400);
        assertThat(eventPageRaw(run.runId(), "?eventType=lowercase").getStatusCode().value())
                .isEqualTo(400);
        assertThat(eventPageRaw(run.runId(), "?eventType=TOOL_STARTED%3B%20DROP").getStatusCode().value())
                .isEqualTo(400);

        // attemptId 与 allAttempts 互斥
        ResponseEntity<String> conflict = eventPageRaw(run.runId(),
                "?attemptId=" + run.attemptId() + "&allAttempts=true");
        assertThat(conflict.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(conflict).path("field").asText()).isEqualTo("attemptId");

        // 未知事件类型是合法过滤条件：返回空页，而不是 400
        JsonNode empty = eventPage(run.runId(), "?eventType=TOOL_STARTED");
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("没有事件的 Run 返回空页，不虚构占位事件")
    void emptyTraceShouldReturnEmptyPage() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String noEventsLine = "{\"schemaVersion\":1,\"targetKey\":\"historical_a\",\"caseKey\":\"case-1\","
                + "\"repeatIndex\":1,\"sourceSystem\":\"manual\",\"sourceRecordId\":\"record-001\","
                + "\"taskOutcome\":\"UNKNOWN\",\"events\":[]}";
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(noEventsLine)), "it-trace-empty-" + scenario.suffix()));
        process(batchId);

        String runId = jdbc.queryForObject("SELECT id FROM evaluation_runs WHERE experiment_id = ?",
                String.class, experimentId);
        JsonNode page = eventPage(runId, "");
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isMissingNode() || page.path("nextCursor").isNull()).isTrue();

        // 详情的事件数同样为 0；完整度标记为 NONE（不是 COMPLETE）
        JsonNode detail = data(get("/api/v1/runs/" + runId));
        assertThat(detail.path("traceEventCount").asLong()).isZero();
        assertThat(detail.path("observationMetadata").path("traceCoverage").asText()).isEqualTo("NONE");
        assertThat(detail.path("observationMetadata").path("outputKnown").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("跨 Run 的 Artifact 引用不进入证据列表")
    void eventArtifactShouldBeScopedToItsRun() {
        Scenario scenario = seedPublished(1);
        Scenario other = seedPublished(1);
        OnlineRun first = runOnline(scenario, "it-trace-artifact-a-" + scenario.suffix());
        OnlineRun second = runOnline(other, "it-trace-artifact-b-" + other.suffix());

        String ownArtifact = uploadArtifactForRun(first.runId(), "own");
        String foreignArtifact = uploadArtifactForRun(second.runId(), "foreign");

        // 直接把两个引用写到第一条 Run 的两条事件上：模拟采集端写错归属的脏数据
        List<String> ids = jdbc.queryForList("SELECT id FROM trace_events WHERE run_id = ?"
                + " ORDER BY occurred_at, id", String.class, first.runId());
        jdbc.update("UPDATE trace_events SET artifact_id = ? WHERE id = ?", ownArtifact, ids.get(0));
        jdbc.update("UPDATE trace_events SET artifact_id = ? WHERE id = ?", foreignArtifact, ids.get(1));

        JsonNode page = eventPage(first.runId(), "?limit=100");
        assertThat(page.path("items").get(0).path("artifactId").asText()).isEqualTo(ownArtifact);
        // 跨 Run 的引用被抹成"无附件"，而不是变成一个可点开的证据
        assertThat(page.path("items").get(1).hasNonNull("artifactId")).isFalse();

        // 数据库里的原始引用仍在：接口是"不暴露"，不是"改写证据"
        assertThat(jdbc.queryForObject("SELECT artifact_id FROM trace_events WHERE id = ?",
                String.class, ids.get(1))).isEqualTo(foreignArtifact);
    }

    @Test
    @DisplayName("大量事件：单页受 limit 约束，SQL 走 (run_id, occurred_at, id) 索引")
    void largeTraceShouldStayBounded() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());
        JsonNode task = claim("it-trace-large-" + scenario.suffix(), 1).get(0);
        String runId = task.path("runId").asText();
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();

        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            batch.add(Map.of("eventId", Ulid.newId(), "eventType", "MODEL_REQUEST",
                    "sequenceNo", i, "occurredAt", occurredAt(i),
                    "payload", Map.of("model", "mock-model", "requestSummary", "q" + i)));
        }
        ids.addAll(appendEvents(attemptId, token, batch));
        List<Map<String, Object>> second = new ArrayList<>();
        for (int i = 500; i < 800; i++) {
            second.add(Map.of("eventId", Ulid.newId(), "eventType", "MODEL_RESPONSE",
                    "sequenceNo", i, "occurredAt", occurredAt(i),
                    "payload", Map.of("model", "mock-model", "outputSummary", "a" + i)));
        }
        ids.addAll(appendEvents(attemptId, token, second));
        assertThat(complete(attemptId, token, "SUCCESS",
                objectMapper.createObjectNode().put("answer", "42"), ids).getStatusCode().value())
                .isEqualTo(200);

        JsonNode page = eventPage(runId, "?limit=100");
        assertThat(page.path("items")).hasSize(100);
        assertThat(page.path("hasMore").asBoolean()).isTrue();

        JsonNode next = eventPage(runId, "?limit=100&cursor=" + page.path("nextCursor").asText());
        assertThat(next.path("items")).hasSize(100);
        Set<String> firstIds = new LinkedHashSet<>(eventIdsOf(page));
        assertThat(eventIdsOf(next)).noneMatch(firstIds::contains);

        // 取第 700 条作为游标（轨迹共 800 条、每条间隔 1 秒），游标之后只剩 100 条。
        Map<String, Object> position = jdbc.queryForMap(
                "SELECT occurred_at, id FROM trace_events WHERE run_id = ?"
                        + " ORDER BY occurred_at ASC, id ASC LIMIT 1 OFFSET 699", runId);
        // Connector/J 对 DATETIME(3) 可能返回 Timestamp，也可能返回 LocalDateTime：两种都接住
        Object rawAt = position.get("occurred_at");
        LocalDateTime cursorAt = rawAt instanceof Timestamp ts
                ? ts.toLocalDateTime() : (LocalDateTime) rawAt;
        String rowId = String.valueOf(position.get("id"));

        // 直接取 Mapper 里的真实语句，而不是在测试里另抄一份 SQL：
        // 否则改了 Mapper 这条用例还在断言"另一条查询"。参数类型同样按生产调用传
        // （游标时间是 UTC 墙钟 LocalDateTime——绑定类型会改变 MySQL 的访问路径）。
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", runId);
        params.put("attemptId", attemptId);
        params.put("afterAt", cursorAt);
        params.put("afterId", rowId);
        params.put("limit", 101);
        BoundSql bound = mapperBoundSql("listPage", params);
        Object[] args = bound.getParameterMappings().stream()
                .map(mapping -> params.get(mapping.getProperty())).toArray();

        // 1) 访问路径：命中 (run_id, occurred_at, id) 覆盖索引，排序由索引顺序满足，不回表读 payload_json
        Map<String, Object> plan = jdbc.queryForMap("EXPLAIN " + bound.getSql(), args);
        assertThat(String.valueOf(plan.get("key")))
                .as("分页应命中键集索引，实际计划: %s", plan)
                .isEqualTo("idx_trace_events_run_id_occurred_at_id");
        assertThat(String.valueOf(plan.get("Extra")))
                .as("应只读索引项、不读 payload，实际计划: %s", plan)
                .contains("Using index");
        assertThat(String.valueOf(plan.get("Extra")))
                .as("索引顺序已经是 occurred_at,id，不该再文件排序: %s", plan)
                .doesNotContain("filesort");

        // 2) 实际读取量：EXPLAIN ANALYZE 真跑一次，看各算子实际处理了多少行。
        //    游标之后只有 100 条事件，读取量应与一页相当；若优化器退化成 run_id 的 ref 扫描，
        //    这里会看到整条轨迹的 800 行，用例失败——"返回 200 且有 nextCursor"不算通过。
        String analyzed = jdbc.queryForObject("EXPLAIN ANALYZE " + bound.getSql(), String.class, args);
        assertThat(maxRowsExamined(analyzed))
                .as("深页只应读一页的索引项，实际执行:%n%s", analyzed)
                .isLessThan(300);
    }

    /** 从 MyBatis 取真实语句：断言的对象必须是 Mapper 里那条查询，而不是测试里重写的一份。 */
    private BoundSql mapperBoundSql(String id, Map<String, Object> params) {
        return sqlSessionFactory.getConfiguration()
                .getMappedStatement("com.repopilot.modules.trace.mapper.TraceEventMapper." + id)
                .getBoundSql(params);
    }

    /** EXPLAIN ANALYZE 输出中最大的 {@code rows=} 计数，即各算子实际处理的行数。 */
    private static long maxRowsExamined(String analyzeOutput) {
        Matcher matcher = Pattern.compile("rows=(\\d+)").matcher(analyzeOutput);
        long max = 0;
        while (matcher.find()) {
            max = Math.max(max, Long.parseLong(matcher.group(1)));
        }
        return max;
    }

    @Test
    @DisplayName("失败 Attempt 的轨迹不混入默认查询，allAttempts 才展开")
    void failedAttemptShouldNotLeakIntoDefaultScope() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode first = claim("it-trace-retry-" + scenario.suffix(), 1).get(0);
        String runId = first.path("runId").asText();
        String firstAttempt = first.path("attemptId").asText();
        appendEvents(firstAttempt, first.path("leaseToken").asText(), sampleEvents());
        assertThat(fail(firstAttempt, first.path("leaseToken").asText(),
                "UPSTREAM_UNAVAILABLE", true).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE evaluation_runs SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), runId);
        workService.maintain();

        JsonNode second = claim("it-trace-retry-" + scenario.suffix(), 1).get(0);
        assertThat(second.path("attemptNo").asInt()).isEqualTo(2);
        String secondAttempt = second.path("attemptId").asText();
        List<String> ids = appendEvents(secondAttempt, second.path("leaseToken").asText(),
                sampleEvents());
        complete(secondAttempt, second.path("leaseToken").asText(), "SUCCESS",
                objectMapper.createObjectNode().put("answer", "42"), ids);

        JsonNode byDefault = eventPage(runId, "?limit=100");
        assertThat(byDefault.path("scope").path("resolvedFrom").asText()).isEqualTo("ACCEPTED");
        assertThat(byDefault.path("scope").path("attemptId").asText()).isEqualTo(secondAttempt);
        assertThat(byDefault.path("items")).hasSize(5);
        assertThat(byDefault.path("items")).allSatisfy(item ->
                assertThat(item.path("attemptId").asText()).isEqualTo(secondAttempt));

        JsonNode all = eventPage(runId, "?limit=100&allAttempts=true");
        assertThat(all.path("scope").path("resolvedFrom").asText()).isEqualTo("ALL");
        assertThat(all.path("items")).hasSize(10);
        Set<String> attemptsInAll = new LinkedHashSet<>();
        all.path("items").forEach(item -> attemptsInAll.add(item.path("attemptId").asText()));
        assertThat(attemptsInAll).containsExactlyInAnyOrder(firstAttempt, secondAttempt);

        JsonNode onlyFirst = eventPage(runId, "?limit=100&attemptId=" + firstAttempt);
        assertThat(onlyFirst.path("scope").path("resolvedFrom").asText()).isEqualTo("REQUESTED");
        assertThat(onlyFirst.path("items")).hasSize(5);
        assertThat(onlyFirst.path("items")).allSatisfy(item ->
                assertThat(item.path("attemptId").asText()).isEqualTo(firstAttempt));
    }
}
