package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-004 的并发不变量：同一 Run 只能有一个有效 Attempt，重复完成只能产生一套 Job。
 *
 * <p>这些用例跑在真 HTTP + 真 MySQL 上。把 {@code SKIP LOCKED} 去掉、或把完成回调里的
 * 状态检查换成"先查后写"，都会在这里失败，而不是等到线上出现两条 Attempt 才发现。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-004 并发领取与完成")
class ExecutionConcurrencyIT extends AbstractExecutionIT {

    private static final int THREADS = 4;

    @Test
    @DisplayName("并发领取：每个 Run 恰好一个 Attempt，领取结果互不重叠")
    void concurrentClaimShouldGiveEachRunExactlyOneAttempt() throws Exception {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 4, 1);
        start(experimentId, "start-" + scenario.suffix());
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ? AND status = 'QUEUED'",
                experimentId)).isEqualTo(4);

        List<ResponseEntity<String>> responses = fire(THREADS, () -> {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("workerId", "cc-worker-" + Ulid.newId().substring(20));
            body.putArray("capabilities").add("RUN:MOCK");
            body.put("maxTasks", 1);
            return workerPost("/internal/v1/work/claim", body);
        });

        Set<String> runIds = new HashSet<>();
        for (ResponseEntity<String> response : responses) {
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode tasks = data(response).path("tasks");
            assertThat(tasks.size()).isLessThanOrEqualTo(1);
            tasks.forEach(t -> assertThat(runIds.add(t.path("runId").asText())).isTrue());
        }
        assertThat(runIds).hasSize(THREADS);
        assertThat(count("SELECT COUNT(*) FROM run_attempts a JOIN evaluation_runs r ON r.id = a.run_id"
                + " WHERE r.experiment_id = ?", experimentId)).isEqualTo(THREADS);
        assertThat(count("SELECT COUNT(*) FROM (SELECT run_id FROM run_attempts GROUP BY run_id"
                + " HAVING COUNT(*) > 1) t")).isZero();
        assertThat(count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ? AND status = 'RUNNING'",
                experimentId)).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("并发重复完成：两方都拿到成功回执，Job 只有一套")
    void concurrentDuplicateCompleteShouldCreateOnlyOneJobSet() throws Exception {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("cc-worker", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        String token = task.path("leaseToken").asText();
        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());

        List<ResponseEntity<String>> responses = fire(2, () ->
                complete(attemptId, token, "SUCCESS",
                        objectMapper.createObjectNode().put("answer", "42"), eventIds));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ?", runId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM run_attempts WHERE run_id = ?", runId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluation_batches WHERE experiment_id = ?", experimentId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("取消与完成竞争：不复活为成功，Job 不会留在 PENDING")
    void cancelRacingWithCompleteShouldNotResurrectRun() throws Exception {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "start-" + scenario.suffix());

        JsonNode task = claim("cc-worker", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        String token = task.path("leaseToken").asText();
        List<String> eventIds = appendEvents(attemptId, token, sampleEvents());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> completeFuture = pool.submit(() -> {
                go.await();
                return complete(attemptId, token, "SUCCESS",
                        objectMapper.createObjectNode().put("answer", "42"), eventIds);
            });
            Future<ResponseEntity<String>> cancelFuture = pool.submit(() -> {
                go.await();
                return post("/api/v1/experiments/" + experimentId + "/cancel",
                        "cc-cancel-" + scenario.suffix(), "{\"reason\":\"race\"}");
            });
            go.countDown();
            ResponseEntity<String> completed = completeFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> cancelled = cancelFuture.get(30, TimeUnit.SECONDS);

            assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
            assertThat(completed.getStatusCode().value()).isIn(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM experiments WHERE id = ?", String.class,
                experimentId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM evaluation_jobs WHERE run_id = ? AND status = 'PENDING'", runId))
                .isZero();
    }

    private List<ResponseEntity<String>> fire(int threads, Callable<ResponseEntity<String>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ResponseEntity<String>> results = new ArrayList<>(threads);
            for (Future<ResponseEntity<String>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
