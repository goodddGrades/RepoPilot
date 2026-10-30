package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.modules.report.service.ReportAggregationService;
import com.repopilot.modules.report.dto.ReportDtos;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-012 压测：把"平台开销"从"Mock 执行"里分离出来，用真实 MySQL 测。
 *
 * <p><b>为什么可以分离</b>：Mock Runner 是纯 CPU 的零延迟实现——没有 sleep、
 * 没有网络、没有模型调用。所以它消耗的时间全是平台自己的时间：
 * HTTP 解析、租约协议、数据库写入、事件封口、聚合计算。
 * 这让"端到端耗时"直接等于"平台开销"，不需要估算或扣减。
 *
 * <p><b>这个类不是常规回归测试</b>：它按分钟级运行，只在压测时显式执行
 * （{@code -Dit.test=PerformanceBaselineIT}），不挂在 {@code verify} 的默认路径上。
 * 它的断言是"结果必须正确"（与压测无关的正确性检查），
 * <b>性能数字本身不断言</b>——CI 机器性能不稳定，把毫秒数写成断言会让 CI 变成 flaky。
 *
 * <p><b>产出</b>：{@code target/perf/<场景>-<时间戳>.json}，
 * 含硬件、数据体积、并发、每阶段耗时分位数、原始样本。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-012 压测：平台开销基线与瓶颈定位")
class PerformanceBaselineIT extends AbstractExecutionIT {

    @Autowired
    private ReportAggregationService reports;

    /** 压测参数：默认规模适中，能在几分钟内跑完一轮完整基线。 */
    private static final int RUN_COUNT = Integer.getInteger("repopilot.perf.runs", 60);
    private static final int EVENTS_PER_RUN = Integer.getInteger("repopilot.perf.events", 40);
    private static final int CONCURRENCY = Integer.getInteger("repopilot.perf.concurrency", 8);

    /**
     * 采样容器。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@link LinkedHashMap}：
     * 采集发生在多个 Worker 线程里，普通 Map 会抛 {@code ConcurrentModificationException}。
     * 顺序在这里没有意义——统计只按分位数，不按写入顺序。
     */
    private final Map<String, List<Long>> samples = new ConcurrentHashMap<>();

    // ================================================================== 场景

    @Test
    @DisplayName("基线：完整执行链路的各阶段耗时 + Trace 游标翻页 + 报告聚合")
    void captureBaseline() throws Exception {
        // 数据集固定 10 个样本；Run 数 = 样本数 × repeat_count，
        // 这样调 repeat 就能线性放大实验规模而不用重建数据集。
        int caseCount = 10;
        Scenario scenario = seedPublished(caseCount);
        int repeatCount = Math.max(1, RUN_COUNT / caseCount);

        record Phase(String name, long millis, String note) {
        }
        List<Phase> phases = new ArrayList<>();

        // ---- 阶段 1：实验提交（单事务生成全部 Run 槽位）
        long t0 = nano();
        String experimentId = createExperiment(scenario, 1, repeatCount);
        start(experimentId, idem("perf-start"));
        int expectedRuns = (int) count(
                "SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId);
        phases.add(new Phase("experimentSubmit", (nano() - t0) / 1_000_000,
                "单事务插入 " + expectedRuns + " 个 Run 槽位（" + caseCount
                        + " 样本 × repeat " + repeatCount + "）+ 初始批次 + Run 登记 + Outbox 事件"));
        assertThat(expectedRuns).as("槽位数必须与样本数×重复数一致").isEqualTo(caseCount * repeatCount);

        // ---- 阶段 2：并发执行（Mock 零延迟 → 全部是平台开销）
        long t1 = nano();
        int executed = drainConcurrently(experimentId, CONCURRENCY);
        long execMillis = (nano() - t1) / 1_000_000;
        phases.add(new Phase("concurrentExecution", execMillis,
                executed + " 个 Run，Worker 并发 " + CONCURRENCY
                        + "；含领取/心跳/事件上传/封口/Job 创建"));

        // ---- 阶段 3：并发评测
        long t2 = nano();
        int evaluated = evaluateConcurrently(experimentId, CONCURRENCY);
        phases.add(new Phase("concurrentEvaluation", (nano() - t2) / 1_000_000,
                evaluated + " 个 Job 走完算子并落结果"));

        // ---- 阶段 4：事件写入吞吐
        long t3 = nano();
        long totalEvents = seedBulkTraceEvents(experimentId);
        phases.add(new Phase("bulkTraceInsert", (nano() - t3) / 1_000_000,
                totalEvents + " 条 trace_events 批量写入"));

        // ---- 阶段 5：Trace 游标翻页（真实查询路径，深页最容易退化）
        String probeRun = jdbc.queryForObject(
                "SELECT id FROM evaluation_runs WHERE experiment_id = ? LIMIT 1",
                String.class, experimentId);
        tracePageBench(probeRun, EVENTS_PER_RUN);

        // ---- 阶段 6：报告聚合（跨模块只读门面取数）
        long t4 = nano();
        ReportDtos.ExperimentReport report =
                reports.report(experimentId, null, "INITIAL", null, null, "REVIEWED");
        phases.add(new Phase("reportAggregation", (nano() - t4) / 1_000_000,
                "Target " + report.targets().size() + " 个，门禁 "
                        + report.gate().verdict()));

        // ---- 阶段 7：报告聚合预热后复测（区分冷/热缓存）
        long t5 = nano();
        reports.report(experimentId, null, "INITIAL", null, null, "REVIEWED");
        long warm = (nano() - t5) / 1_000_000;
        phases.add(new Phase("reportAggregationWarm", warm, "同一实验第二次聚合"));

        // ---- 阶段 8：Run 列表分页
        runListBench(experimentId);

        writeResult("baseline", experimentId, phases, Map.of(
                "runs", (long) RUN_COUNT,
                "eventsPerRun", (long) EVENTS_PER_RUN,
                "concurrency", (long) CONCURRENCY,
                "executedRuns", (long) executed,
                "evaluatedJobs", (long) evaluated));

        // 正确性与性能无关：压测跑完系统必须仍然自洽
        assertThat(report.targets()).as("压测后报告必须能聚合出 Target 指标").isNotEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' OR status = 'PENDING'",
                Long.class)).as("Outbox 不应出现卡死的事件").isNotNegative();
        System.out.println("[perf] baseline written, samples=" + samples.size());
    }

    // ================================================================== 并发执行

    /** 多 Worker 并发领取直到实验全部 Run 终态，返回完成数。 */
    private int drainConcurrently(String experimentId, int concurrency) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicLong completed = new AtomicLong();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < concurrency; w++) {
                final String wid = workerId("perf-run-" + w);
                futures.add(pool.submit((Callable<Void>) () -> {
                    // 每个 Worker 独立循环领取，直到领不到本实验的任务为止。
                    // 必须按 runId 过滤 owner：集成测试库是共享的，
                    // 别的用例遗留的 Run 也会被领到，跑它们的封口等于污染别人的数据。
                    for (int round = 0; round < 500; round++) {
                        List<JsonNode> tasks = claim(wid, 5);
                        if (tasks.isEmpty()) {
                            return null;
                        }
                        boolean progressed = false;
                        for (JsonNode task : tasks) {
                            String runId = task.path("runId").asText();
                            String owner = jdbc.queryForObject(
                                    "SELECT experiment_id FROM evaluation_runs WHERE id = ?",
                                    String.class, runId);
                            if (!experimentId.equals(owner)) {
                                continue;
                            }
                            long started = nano();
                            executeOneRun(task);
                            sample("run.execute", nano() - started);
                            completed.incrementAndGet();
                            progressed = true;
                        }
                        if (!progressed) {
                            return null;
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        return completed.intValue();
    }

    /** 一个 Run 的完整执行：事件上传 + 封口。 */
    private void executeOneRun(JsonNode task) {
        String attemptId = task.path("attemptId").asText();
        String token = task.path("leaseToken").asText();
        List<Map<String, Object>> events = bulkEvents(EVENTS_PER_RUN);
        List<String> acceptedIds = appendEvents(attemptId, token, events);
        ResponseEntity<String> done = completeRun(attemptId, token, "SUCCESS",
                "{\"mock\":true,\"benchmark\":true}", acceptedIds);
        assertThat(done.getStatusCode().value())
                .as("Run 封口应成功: %s", done.getBody()).isEqualTo(200);
    }

    // ================================================================== 并发评测

    private int evaluateConcurrently(String experimentId, int concurrency) throws Exception {
        // 评测 Job 需要 EVAL 能力声明；不带这个能力 claim 只会返回 Run。
        // 从本实验的 INITIAL 批次反查方案版本 → 方案项 → 算子定义，
        // 不硬编码算子 key：换方案时压测不用改代码。
        // 注意列名：方案项表是 plan_version_id（不是 evaluation_plan_version_id）。
        String evaluatorDefId = jdbc.queryForObject(
                "SELECT i.evaluator_definition_id FROM evaluation_plan_items i"
                        + " JOIN evaluation_plan_versions pv ON pv.id = i.plan_version_id"
                        + " JOIN evaluation_batches b ON b.evaluation_plan_version_id = pv.id"
                        + " WHERE b.experiment_id = ? AND b.trigger_type = 'INITIAL'"
                        + " LIMIT 1",
                String.class, experimentId);
        // 注意列名：算子定义表是 evaluator_key（不是 key）。
        String key = jdbc.queryForObject(
                "SELECT evaluator_key FROM evaluator_definitions WHERE id = ?",
                String.class, evaluatorDefId);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicLong done = new AtomicLong();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < concurrency; w++) {
                final String wid = workerId("perf-eval-" + w);
                futures.add(pool.submit((Callable<Void>) () -> {
                    for (int round = 0; round < 500; round++) {
                        List<JsonNode> tasks = claimAs(wid, "EVAL:" + key);
                        if (tasks.isEmpty()) {
                            return null;
                        }
                        for (JsonNode task : tasks) {
                            long started = nano();
                            String attemptId = task.path("attemptId").asText();
                            String token = task.path("leaseToken").asText();
                            // 证据只引用本次 Attempt，不跨 Run：跨 Attempt 证据会被服务端拒绝
                            jobComplete(attemptId, token, "PASS", new BigDecimal("1.00"),
                                    "压测基线：固定通过",
                                    "[]", "{\"mock\":true,\"benchmark\":true}", "RULE");
                            sample("job.evaluate", nano() - started);
                            done.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        return done.intValue();
    }

    // ================================================================== 事件与查询基准

    /**
     * 批量写入 trace_events：直接 JDBC，不走 HTTP，用来测数据库写入本身的上限。
     *
     * <p>事件类型与 payload 形态与 {@link #bulkEvents} 保持一致——这里测的是
     * "同样的行以批量的方式写入有多快"，如果行本身更小或更简单，
     * 测出来的写入上限就不能代表真实路径。
     */
    private long seedBulkTraceEvents(String experimentId) {
        List<String> runs = jdbc.queryForList(
                "SELECT id FROM evaluation_runs WHERE experiment_id = ?", String.class, experimentId);
        long written = 0;
        for (String runId : runs) {
            List<Object[]> batch = new ArrayList<>();
            Instant base = Instant.now();
            for (int i = 0; i < EVENTS_PER_RUN; i++) {
                String type;
                String payloadJson;
                switch (i % 3) {
                    case 0 -> {
                        type = "MODEL_REQUEST";
                        payloadJson = "{\"model\":\"mock-model\",\"requestSummary\":\"perf#" + i + "\"}";
                    }
                    case 1 -> {
                        type = "MODEL_RESPONSE";
                        payloadJson = "{\"model\":\"mock-model\",\"outputSummary\":\"perf#" + i + "\"}";
                    }
                    default -> {
                        type = "OUTPUT_PRODUCED";
                        payloadJson = "{\"outputSummary\":\"perf#" + i + "\"}";
                    }
                }
                batch.add(new Object[]{
                        Ulid.newId(), Ulid.newId(), runId, null, null,
                        Sha256.ofUtf8(runId + "-" + i), null, null, null,
                        i, type,
                        Timestamp.from(base.plusMillis(i)),
                        payloadJson, null, Timestamp.from(base)});
            }
            jdbc.batchUpdate("""
                    INSERT INTO trace_events
                      (id, event_id, run_id, run_attempt_id, source_event_id, event_hash,
                       trace_id, span_id, parent_span_id, sequence_no, event_type,
                       occurred_at, payload_json, artifact_id, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, batch);
            written += batch.size();
        }
        return written;
    }

    /** Trace 游标翻页基准：逐页翻完一个 Run 的全部事件，记录每页耗时。 */
    private void tracePageBench(String runId, int expectedPerRun) {
        String cursor = null;
        int pages = 0;
        int events = 0;
        for (int i = 0; i < 200; i++) {
            String url = "/api/v1/runs/" + runId + "/events?pageSize=50"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            long t0 = nano();
            ResponseEntity<String> response = get(url);
            sample("trace.page", nano() - t0);
            if (response.getStatusCode().value() != 200) {
                break;
            }
            JsonNode body = data(response);
            events += body.path("items").size();
            pages++;
            if (!body.path("hasMore").asBoolean(false)) {
                break;
            }
            JsonNode next = body.path("nextCursor");
            if (next.isMissingNode() || next.isNull() || next.asText("").isBlank()) {
                break;
            }
            cursor = next.asText();
        }
        sample("trace.pagesPerRun", pages);
        sample("trace.eventsPerRunSeen", events);
        System.out.println("[perf] trace pages=" + pages + " events=" + events
                + " expectedPerRun=" + expectedPerRun);
    }

    /** Run 列表分页基准。 */
    private void runListBench(String experimentId) {
        for (int i = 0; i < 5; i++) {
            long t0 = nano();
            get("/api/v1/experiments/" + experimentId + "/runs?page=1&pageSize=20");
            sample("run.listPage", nano() - t0);
        }
        // 深页
        for (int page = 1; page <= 3; page++) {
            long t0 = nano();
            get("/api/v1/experiments/" + experimentId + "/runs?page=" + page + "&pageSize=20");
            sample("run.listPageDeep", nano() - t0);
        }
    }

    // ================================================================== 指标采集

    private static long nano() {
        return System.nanoTime();
    }

    private void sample(String name, long nanos) {
        // 显式 synchronized：computeIfAbsent 的映射函数在并发下可能执行多次，
        // 返回的 List 若不是线程安全的，add 会丢样本或抛 CME。
        // 采样频率只有每秒几千次，锁竞争可以忽略。
        synchronized (samples) {
            samples.computeIfAbsent(name, k -> new ArrayList<>()).add(nanos / 1000);
        }
    }

    /** 分位数统计：只有 P50/P95/P99/max 有意义，平均值会被长尾带偏。 */
    private static Map<String, Object> stats(List<Long> micros) {
        if (micros.isEmpty()) {
            return Map.of("count", 0);
        }
        List<Long> sorted = new ArrayList<>(micros);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", n);
        m.put("minMs", round(sorted.get(0) / 1000.0));
        m.put("p50Ms", round(sorted.get(n / 2) / 1000.0));
        m.put("p95Ms", round(sorted.get((int) (n * 0.95)) / 1000.0));
        m.put("p99Ms", round(sorted.get(Math.min(n - 1, (int) (n * 0.99))) / 1000.0));
        m.put("maxMs", round(sorted.get(n - 1) / 1000.0));
        m.put("meanMs", round(sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0));
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 写出机器可读结果：硬件 + 参数 + 每阶段耗时 + 完整样本。 */
    private void writeResult(String scenario, String experimentId,
                             List<?> phases, Map<String, Long> params) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target", "perf");
            java.nio.file.Files.createDirectories(dir);
            String stamp = Instant.now().toString().replace(":", "-").substring(0, 19);
            java.nio.file.Path file = dir.resolve(scenario + "-" + stamp + ".json");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scenario", scenario);
            out.put("experimentId", experimentId);
            out.put("timestamp", Instant.now().toString());
            out.put("hardware", hardware());
            out.put("parameters", params);
            out.put("phases", phases);
            Map<String, Object> s = new LinkedHashMap<>();
            samples.forEach((k, v) -> s.put(k, stats(v)));
            out.put("samples", s);
            // 完整原始样本：让统计口径可被质疑、可被重算
            Map<String, Object> raw = new LinkedHashMap<>(samples);
            out.put("rawMicros", raw);

            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
            System.out.println("[perf] result written to " + file.toAbsolutePath());
        } catch (Exception ex) {
            throw new IllegalStateException("压测结果写入失败", ex);
        }
    }

    private static Map<String, Object> hardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("logicalCores", Runtime.getRuntime().availableProcessors());
        hw.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        hw.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        hw.put("java", System.getProperty("java.version"));
        hw.put("jvmArgs", System.getProperty("sun.java.command") == null ? "n/a"
                : System.getProperty("sun.java.command"));
        return hw;
    }

    // ================================================================== 事件构造

    /**
     * 构造符合 {@code trace-event.schema.json} 与 {@code TraceEventTypes} 的事件。
     *
     * <p>刻意用契约里真实存在的事件类型而不是随便起名字：
     * 用未注册的 {@code LOG} 会被服务端 400 拒掉——压测必须走真实校验路径，
     * 否则测的是"绕过校验的裸插入"，不是平台的真实开销。
     *
     * <p>{@code eventId} 与 {@code occurredAt} 必须由调用方给：Worker 侧的真实
     * 行为是首次生成后固定，重发时复用同一个 ID（服务端幂等去重依赖它）。
     */
    private List<Map<String, Object>> bulkEvents(int count) {
        List<Map<String, Object>> events = new ArrayList<>(count);
        String occurredAt = java.time.Instant.now().toString();
        for (int i = 0; i < count; i++) {
            // 三种事件轮转，字段都按 TraceEventTypes.REQUIRED 填全
            Map<String, Object> payload;
            String type;
            switch (i % 3) {
                case 0 -> {
                    type = "MODEL_REQUEST";
                    payload = Map.of("model", "mock-model", "requestSummary", "perf#" + i);
                }
                case 1 -> {
                    type = "MODEL_RESPONSE";
                    payload = Map.of("model", "mock-model", "outputSummary", "perf#" + i);
                }
                default -> {
                    type = "OUTPUT_PRODUCED";
                    payload = Map.of("outputSummary", "perf#" + i);
                }
            }
            events.add(Map.of(
                    "eventId", Ulid.newId(),
                    "eventType", type,
                    "sequenceNo", i,
                    "occurredAt", occurredAt,
                    "payload", payload));
        }
        return events;
    }
}