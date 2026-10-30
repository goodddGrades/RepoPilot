package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线轨迹导入的验收用例（真实 HTTP + 真实 MySQL）。
 *
 * <p>每个用例都断言三层：HTTP 响应、数据库行、批次计数。只断言 200 无法发现
 * "接口说成功、实际一行没写"这类问题。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OfflineImportIT extends AbstractOfflineIT {

    @Test
    void validJsonlShouldImportRunsAndKeepMissingOutputEmpty() {
        Scenario scenario = seedPublished(3);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 2);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                // 没有 output 的行：缺失就是缺失，不补空对象
                runLine("historical_a", "case-2", 1, "record-002", null)));

        ResponseEntity<String> created = createImport(experimentId, scenario.datasetVersionId(),
                artifactId, "it-import-" + scenario.suffix());
        assertThat(created.getStatusCode().value())
                .as("创建导入应返回 202: %s", created.getBody()).isEqualTo(202);
        String batchId = dataId(created);

        assertThat(batchStatus(batchId)).as("创建后应停在 PENDING").isEqualTo("PENDING");
        process(batchId);

        JsonNode batch = batch(batchId);
        assertThat(batch.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.path("totalCount").asLong()).isEqualTo(2);
        assertThat(batch.path("totalKnown").asBoolean()).isTrue();
        assertThat(batch.path("successCount").asLong()).isEqualTo(2);
        assertThat(batch.path("failedCount").asLong()).isZero();
        assertThat(batch.path("duplicateCount").asLong()).isZero();
        assertThat(batch.path("lastLineNo").asLong()).isEqualTo(2);

        assertThat(runCount(experimentId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND mode = 'OFFLINE' AND status = 'PENDING' AND sealed_at IS NOT NULL"
                + " AND attempt_count = 0 AND accepted_attempt_id IS NULL", experimentId)).isEqualTo(2);
        // 缺输出保留 NULL：这列是"有没有答案"的唯一真相
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-002' AND output_json IS NULL", experimentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-001' AND output_json IS NOT NULL", experimentId)).isEqualTo(1);

        JsonNode detail = data(get("/api/v1/experiments/" + experimentId));
        assertThat(detail.path("importedRunCount").asLong()).isEqualTo(2);
        assertThat(detail.path("missingRunCount").asLong())
                .as("3 个样本 × 2 次重复 = 6 个槽位，只导入了 2 条").isEqualTo(4);
    }

    @Test
    void startShouldFreezeRunsAndCreatePendingJobsWithoutScores() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 2);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                runLine("historical_a", "case-2", 1, "record-002", "{\"answer\":\"3\"}")));
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-" + scenario.suffix()));
        process(batchId);

        ResponseEntity<String> started = start(experimentId, "it-start-" + scenario.suffix());
        assertThat(started.getStatusCode().value())
                .as("启动应返回 202: %s", started.getBody()).isEqualTo(202);
        assertThat(data(started).path("status").asText()).isEqualTo("QUEUED");

        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND status = 'EVALUATING'", experimentId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?"
                + " AND trigger_type = 'INITIAL' AND expected_job_count = 2", experimentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batch_runs WHERE experiment_id = ?",
                experimentId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs j JOIN evaluation_batches b"
                + " ON b.id = j.batch_id WHERE b.experiment_id = ? AND j.status = 'PENDING'",
                experimentId)).isEqualTo(2);
        // 关键：没有任何评分被写出来，也没有伪造执行尝试
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs j JOIN evaluation_batches b"
                + " ON b.id = j.batch_id WHERE b.experiment_id = ?", experimentId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM evaluation_results r JOIN evaluation_jobs j"
                + " ON j.id = r.job_id JOIN evaluation_batches b ON b.id = j.batch_id"
                + " WHERE b.experiment_id = ?", experimentId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM run_attempts a JOIN evaluation_runs r"
                + " ON r.id = a.run_id WHERE r.experiment_id = ?", experimentId)).isZero();
    }

    @Test
    void unknownCaseKeyShouldRejectOnlyThatLine() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                runLine("historical_a", "case-404", 1, "record-002", "{\"answer\":\"2\"}")));
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-" + scenario.suffix()));
        process(batchId);

        JsonNode batch = batch(batchId);
        assertThat(batch.path("status").asText()).isEqualTo("PARTIAL_FAILED");
        assertThat(batch.path("successCount").asLong()).isEqualTo(1);
        assertThat(batch.path("failedCount").asLong()).isEqualTo(1);
        assertThat(errorCodes(batchId)).containsExactly("CASE_NOT_FOUND");
        assertThat(runCount(experimentId)).as("失败行不能留下游离 Run").isEqualTo(1);
    }

    @Test
    void malformedLinesShouldReportEachProblemAndWriteNothing() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                "{not json",
                "{\"schemaVersion\":1,\"caseKey\":\"case-1\"}",
                "{\"schemaVersion\":1,\"targetKey\":\"historical_a\",\"caseKey\":\"case-1\","
                        + "\"repeatIndex\":1,\"sourceSystem\":\"manual\",\"sourceRecordId\":\"r-3\","
                        + "\"taskOutcome\":\"UNKNOWN\",\"events\":[],\"startedAt\":\"2026-10-17T04:00:00\"}",
                "{\"schemaVersion\":1,\"targetKey\":\"historical_a\",\"caseKey\":\"case-1\","
                        + "\"repeatIndex\":1,\"sourceSystem\":\"manual\",\"sourceRecordId\":\"r-4\","
                        + "\"taskOutcome\":\"UNKNOWN\",\"events\":[],\"unexpectedField\":1}",
                runLine("historical_a", "case-1", 0, "r-5", "{}")));
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-" + scenario.suffix()));
        process(batchId);

        JsonNode batch = batch(batchId);
        assertThat(batch.path("status").asText()).isEqualTo("FAILED");
        assertThat(batch.path("successCount").asLong()).isZero();
        assertThat(batch.path("failedCount").asLong()).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM import_records WHERE batch_id = ? AND status = 'REJECTED'",
                batchId)).isEqualTo(5);
        assertThat(errorCodes(batchId)).contains(
                "INVALID_JSON", "SCHEMA_VIOLATION", "INVALID_TIMESTAMP");
        assertThat(runCount(experimentId)).isZero();
        // 错误行号必须是编辑器里能跳过去的物理行号
        assertThat(importErrors(batchId)).extracting(e -> e.path("lineNo").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void emptyFileShouldBeRejectedBeforeBatchIsCreated() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadBytes("\n \n\n".getBytes(StandardCharsets.UTF_8), "empty.jsonl");

        ResponseEntity<String> response = createImport(experimentId, scenario.datasetVersionId(),
                artifactId, "it-import-empty-" + scenario.suffix());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE experiment_id = ?", experimentId))
                .as("坏文件不应留下批次").isZero();
    }

    @Test
    void finishedBeforeStartedShouldBeRejected() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String line = runLine("historical_a", "case-1", 1, "record-001", "{}")
                .replace("\"startedAt\":\"2026-10-17T12:00:00.000+08:00\"",
                        "\"startedAt\":\"2026-10-17T12:00:10.000+08:00\"");
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(line)), "it-import-time-" + scenario.suffix()));
        process(batchId);

        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        assertThat(errorCodes(batchId)).containsExactly("INVALID_TIME_RANGE");
        assertThat(runCount(experimentId)).isZero();
    }

    @Test
    void replayingSameFileInAnotherBatchShouldReuseRuns() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                runLine("historical_a", "case-2", 1, "record-002", "{\"answer\":\"3\"}")));

        String first = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-a-" + scenario.suffix()));
        process(first);
        assertThat(batchStatus(first)).isEqualTo("COMPLETED");

        String second = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-b-" + scenario.suffix()));
        process(second);

        JsonNode replayed = batch(second);
        assertThat(replayed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(replayed.path("successCount").asLong()).isEqualTo(2);
        assertThat(replayed.path("duplicateCount").asLong())
                .as("duplicate_count 是 success_count 的子集").isEqualTo(2);
        assertThat(replayed.path("failedCount").asLong()).isZero();
        assertThat(runCount(experimentId)).as("跨批次复用不得新增 Run").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM import_records WHERE batch_id = ? AND status = 'REUSED'",
                second)).isEqualTo(2);
        // import_batch_id 保持首次批次，不被重导覆盖
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND import_batch_id = ?", experimentId, first)).isEqualTo(2);
    }

    @Test
    void sameSourceWithDifferentBodyShouldConflict() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String firstArtifact = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}")));
        String first = dataId(createImport(experimentId, scenario.datasetVersionId(), firstArtifact,
                "it-import-src-a-" + scenario.suffix()));
        process(first);
        assertThat(batchStatus(first)).isEqualTo("COMPLETED");

        String conflicting = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"99\"}")));
        String second = dataId(createImport(experimentId, scenario.datasetVersionId(), conflicting,
                "it-import-src-b-" + scenario.suffix()));
        process(second);

        assertThat(batchStatus(second)).isEqualTo("FAILED");
        assertThat(errorCodes(second)).containsExactly("SOURCE_RECORD_CONFLICT");
        assertThat(runCount(experimentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-001' AND output_json IS NOT NULL", experimentId))
                .as("原轨迹不得被覆盖").isEqualTo(1);
    }

    @Test
    void sameSlotFromDifferentSourceShouldConflict() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String first = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-001", "{}"))),
                "it-import-slot-a-" + scenario.suffix()));
        process(first);

        String second = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-999", "{}"))),
                "it-import-slot-b-" + scenario.suffix()));
        process(second);

        assertThat(batchStatus(second)).isEqualTo("FAILED");
        assertThat(errorCodes(second)).containsExactly("SLOT_CONFLICT");
        assertThat(runCount(experimentId)).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReplayShouldReturnSameBatchAndConflictOtherwise() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{}")));
        String key = "it-import-idem-" + scenario.suffix();

        String firstId = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId, key));
        ResponseEntity<String> replayed = createImport(experimentId, scenario.datasetVersionId(),
                artifactId, key);
        assertThat(replayed.getStatusCode().value()).isEqualTo(202);
        assertThat(dataId(replayed)).as("同 key 同正文必须复用原批次").isEqualTo(firstId);

        ResponseEntity<String> conflict = createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-002", "{}"))), key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE experiment_id = ?", experimentId))
                .isEqualTo(1);
    }

    @Test
    void importAfterStartShouldBeRejected() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String first = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-001", "{}"))),
                "it-import-before-" + scenario.suffix()));
        process(first);
        start(experimentId, "it-start-" + scenario.suffix());

        ResponseEntity<String> late = createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-777", "{}"))),
                "it-import-after-" + scenario.suffix());
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE experiment_id = ?", experimentId))
                .as("开始后不得再建批次").isEqualTo(1);
    }

    @Test
    void cancelBeforeProcessingShouldKeepBatchCancelledAndWriteNothing() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(
                        runLine("historical_a", "case-1", 1, "record-001", "{}"),
                        runLine("historical_a", "case-2", 1, "record-002", "{}"))),
                "it-import-cancel-" + scenario.suffix()));

        ResponseEntity<String> cancelled = post("/api/v1/imports/" + batchId + "/cancel", null, "{}");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");

        // 取消之后即便还有线程想继续，也不能提交新行
        process(batchId);
        assertThat(batchStatus(batchId)).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM import_records WHERE batch_id = ?", batchId)).isZero();
        assertThat(runCount(experimentId)).isZero();
    }

    @Test
    void resumeAfterCrashBeforeFinalizeShouldNotDuplicateRuns() {
        Scenario scenario = seedPublished(2);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(
                runLine("historical_a", "case-1", 1, "record-001", "{}"),
                runLine("historical_a", "case-2", 1, "record-002", "{}")));
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(), artifactId,
                "it-import-crash-" + scenario.suffix()));
        process(batchId);
        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");

        // 模拟"所有行都已提交、但收尾写入前进程崩溃"：批次仍停在 RUNNING
        jdbc.update("UPDATE import_batches SET status = 'RUNNING', finished_at = NULL WHERE id = ?", batchId);
        process(batchId);

        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
        assertThat(runCount(experimentId)).as("重放不得新增 Run").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM import_records WHERE batch_id = ?", batchId)).isEqualTo(2);
        assertThat(batch(batchId).path("successCount").asLong()).isEqualTo(2);
    }

    @Test
    void datasetCasesImportTypeShouldPointToTheAtomicEndpoint() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String artifactId = uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-001", "{}")));

        ResponseEntity<String> response = post("/api/v1/imports", "it-import-cases-" + scenario.suffix(),
                """
                {"importType":"DATASET_CASES","datasetVersionId":"%s","experimentId":"%s",
                 "fileArtifactId":"%s"}
                """.formatted(scenario.datasetVersionId(), experimentId, artifactId));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(firstError(response).path("message").asText()).contains("cases:import");
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE experiment_id = ?", experimentId))
                .isZero();
    }

    @Test
    void artifactMetadataShouldNotLeakStoragePathAndDownloadShouldReturnBytes() {
        byte[] content = ("{\"schemaVersion\":1}\n").getBytes(StandardCharsets.UTF_8);
        String artifactId = uploadBytes(content, "one.jsonl");

        JsonNode artifact = data(get("/api/v1/artifacts/" + artifactId));
        assertThat(artifact.path("objectKey").isMissingNode())
                .as("不得把存储对象键暴露给调用方").isTrue();
        assertThat(artifact.toString()).doesNotContain("var/storage").doesNotContain("imports/");
        assertThat(artifact.path("sha256").asText()).hasSize(64);

        ResponseEntity<byte[]> download = restTemplate.getForEntity(
                "/api/v1/artifacts/" + artifactId + "/download", byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getBody()).isEqualTo(content);
    }

    @Test
    void unknownTargetAndOutOfRangeRepeatShouldBeRejected() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 2);
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(
                        runLine("historical_b", "case-1", 1, "record-001", "{}"),
                        runLine("historical_a", "case-1", 3, "record-002", "{}"))),
                "it-import-target-" + scenario.suffix()));
        process(batchId);

        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        assertThat(errorCodes(batchId)).containsExactly("TARGET_NOT_FOUND", "REPEAT_INDEX_OUT_OF_RANGE");
        assertThat(runCount(experimentId)).isZero();
    }

    @Test
    void runDetailShouldExposeTraceEvidenceForOfflineRun() {
        Scenario scenario = seedPublished(1);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(runLine("historical_a", "case-1", 1, "record-001",
                        "{\"answer\":\"2\"}"))),
                "it-import-detail-" + scenario.suffix()));
        process(batchId);
        start(experimentId, "it-start-" + scenario.suffix());

        String runId = jdbc.queryForObject(
                "SELECT id FROM evaluation_runs WHERE experiment_id = ?", String.class, experimentId);
        JsonNode detail = data(get("/api/v1/runs/" + runId));
        // 详情把 Run 本体嵌在 run 里，输出与事件数在顶层
        assertThat(detail.path("run").path("status").asText()).isEqualTo("EVALUATING");
        assertThat(detail.path("run").path("mode").asText()).isEqualTo("OFFLINE");
        assertThat(detail.path("run").path("attemptCount").asInt()).isZero();
        // 离线 Run 没有执行尝试：acceptedAttemptId 必须缺省（全局 NON_NULL 序列化会省略该字段）
        assertThat(detail.path("run").hasNonNull("acceptedAttemptId")).isFalse();
        assertThat(detail.path("traceEventCount").asLong()).isEqualTo(1);
        assertThat(detail.path("output").path("answer").asText()).isEqualTo("2");
        // 来源是追溯离线轨迹的第一手依据，必须原样透出
        assertThat(detail.path("sourceSystem").asText()).isEqualTo("manual");
        assertThat(detail.path("sourceRecordId").asText()).isEqualTo("record-001");
        assertThat(detail.path("attempts")).isEmpty();

        // 轨迹真的落库了：类型、发生时间、载荷都来自原始行
        assertThat(count("SELECT COUNT(*) FROM trace_events WHERE run_id = ?"
                + " AND run_attempt_id IS NULL AND event_type = 'OUTPUT_PRODUCED'"
                + " AND source_event_id = 'evt-1' AND sequence_no = 1"
                + " AND event_hash IS NOT NULL", runId)).isEqualTo(1);
        // 离线事件不得伪造执行尝试
        assertThat(count("SELECT COUNT(*) FROM run_attempts a JOIN evaluation_runs r"
                + " ON r.id = a.run_id WHERE r.id = ?", runId)).isZero();
    }

    /**
     * 轨迹完整度标记必须保守推导，不能把"行的自称"当事实。
     *
     * <p>规则来自 {@code docs/modules/trace-pipeline.md}：未声明时按是否显式给出 output 与是否有事件推导，
     * 且只有经验证的适配器才能声明 COMPLETE——JSONL 导入没有适配器可核验，所以自报 COMPLETE 只能是 PARTIAL。
     */
    @Test
    void observationMetadataShouldBeDerivedConservativelyFromTheLine() {
        Scenario scenario = seedPublished(3);
        String experimentId = createOfflineExperiment(scenario, "historical_a", 1);
        String noOutputNoEvents = "{\"schemaVersion\":1,\"targetKey\":\"historical_a\",\"caseKey\":\"case-2\","
                + "\"repeatIndex\":1,\"sourceSystem\":\"manual\",\"sourceRecordId\":\"record-002\","
                + "\"taskOutcome\":\"UNKNOWN\",\"events\":[]}";
        String claimsComplete = "{\"schemaVersion\":1,\"targetKey\":\"historical_a\",\"caseKey\":\"case-3\","
                + "\"repeatIndex\":1,\"sourceSystem\":\"manual\",\"sourceRecordId\":\"record-003\","
                + "\"taskOutcome\":\"UNKNOWN\",\"output\":{\"answer\":\"4\"},"
                + "\"observationMetadata\":{\"traceCoverage\":\"COMPLETE\"},"
                + "\"events\":[{\"eventType\":\"OUTPUT_PRODUCED\","
                + "\"occurredAt\":\"2026-10-17T12:00:05.000+08:00\","
                + "\"payload\":{\"outputSummary\":\"4\"}}]}";

        String batchId = dataId(createImport(experimentId, scenario.datasetVersionId(),
                uploadLines(List.of(
                        runLine("historical_a", "case-1", 1, "record-001", "{\"answer\":\"2\"}"),
                        noOutputNoEvents,
                        claimsComplete)),
                "it-import-observation-" + scenario.suffix()));
        process(batchId);

        assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");

        // 有输出、有事件、未声明：PARTIAL + outputKnown=true
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-001'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.outputKnown')) = 'true'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.traceCoverage')) = 'PARTIAL'",
                experimentId)).isEqualTo(1);
        // 无输出、无事件：NONE + outputKnown=false，不能补一个空对象冒充"有答案"
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-002'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.outputKnown')) = 'false'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.traceCoverage')) = 'NONE'",
                experimentId)).isEqualTo(1);
        // 自报 COMPLETE：没有可核验的离线适配器，只能记 PARTIAL
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-003'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.outputKnown')) = 'true'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(observation_metadata_json, '$.traceCoverage')) = 'PARTIAL'",
                experimentId)).isEqualTo(1);
        // 无事件的行不得产生任何 Trace 行
        assertThat(count("SELECT COUNT(*) FROM trace_events e JOIN evaluation_runs r"
                + " ON r.id = e.run_id WHERE r.source_record_id = 'record-002'")).isZero();

        // API 与库内一致：详情回显的就是这份派生元数据
        String runId = jdbc.queryForObject("SELECT id FROM evaluation_runs WHERE experiment_id = ?"
                + " AND source_record_id = 'record-002'", String.class, experimentId);
        JsonNode detail = data(get("/api/v1/runs/" + runId));
        assertThat(detail.path("observationMetadata").path("outputKnown").asBoolean()).isFalse();
        assertThat(detail.path("observationMetadata").path("traceCoverage").asText()).isEqualTo("NONE");
        assertThat(detail.path("traceEventCount").asLong()).isZero();
        assertThat(detail.path("attempts")).isEmpty();
    }
}
