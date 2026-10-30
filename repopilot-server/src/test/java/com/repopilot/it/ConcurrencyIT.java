package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发验收（对应手册 C1–C4）。
 *
 * <p>这里的断言不是"接口没报错"，而是"并发之后数据仍然只有一个"——
 * 版本号不重复、caseKey 只有一行、导入只产生一个批次、发布只有一次生效。
 * 四类场景共用同一个入口：所有线程在闸门前就绪，放行后同时发出请求，
 * 因此测的是真实的锁竞争，而不是顺序调用冒充的"并发"。
 *
 * <p>为什么必须用真 HTTP + 真 MySQL：乐观路径在本机内存里永远是对的，
 * 唯一键冲突、行锁等待、事务隔离级别的影响只在真实数据库上才出现。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyIT extends AbstractApiIT {

    /** 并发度：高于 2 才可能真正交叉，4 已经能稳定打到锁竞争，再多只会拉长测试时间。 */
    private static final int THREADS = 4;

    // ------------------------------------------------------------ C1 版本号

    @Test
    void concurrentDatasetVersionCreationShouldNotDuplicateVersionNumbers() throws Exception {
        String datasetId = createDataset("c1-ds");

        List<ResponseEntity<String>> responses = fire(THREADS, () ->
                post("/api/v1/datasets/" + datasetId + "/versions", Ulid.newId(), "{}"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));

        Set<Integer> versions = new TreeSet<>();
        for (ResponseEntity<String> response : responses) {
            versions.add(data(response).path("version").asInt());
        }
        // 4 个线程拿到 1/2/3/4 各一次：重复就说明 nextVersion 没被父行锁保护住
        assertThat(versions).containsExactly(1, 2, 3, 4);
        assertThat(count("SELECT COUNT(*) FROM dataset_versions WHERE dataset_id = ?", datasetId))
                .isEqualTo(THREADS);
        assertThat(count("SELECT COUNT(DISTINCT version) FROM dataset_versions WHERE dataset_id = ?", datasetId))
                .isEqualTo(THREADS);
    }

    @Test
    void concurrentPlanVersionCreationShouldNotDuplicateVersionNumbers() throws Exception {
        String planId = createPlan("c1-plan");
        String evaluatorId = evaluatorId("output.non_empty");
        String body = "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"" + evaluatorId
                + "\",\"executionOrder\":1}]}";

        List<ResponseEntity<String>> responses = fire(THREADS, () ->
                post("/api/v1/evaluation-plans/" + planId + "/versions", Ulid.newId(), body));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));

        Set<Integer> versions = new TreeSet<>();
        for (ResponseEntity<String> response : responses) {
            versions.add(data(response).path("version").asInt());
        }
        assertThat(versions).containsExactly(1, 2, 3, 4);
        assertThat(count("SELECT COUNT(*) FROM evaluation_plan_versions WHERE plan_id = ?", planId))
                .isEqualTo(THREADS);
    }

    // ------------------------------------------------------------ C2 caseKey

    @Test
    void concurrentSameCaseKeyShouldCreateExactlyOneRow() throws Exception {
        String versionId = createDraftVersion("c2-ds");
        String body = "{\"caseKey\":\"race\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"p\"},\"expected\":{\"answer\":\"ok\"}}";

        // 每个线程用各自的幂等键：这里要测的是 caseKey 唯一性，不是幂等重放
        List<ResponseEntity<String>> responses = fire(THREADS, () ->
                post("/api/v1/dataset-versions/" + versionId + "/cases", Ulid.newId(), body));

        long created = responses.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long conflicted = responses.stream().filter(r -> r.getStatusCode().value() == 409).count();

        assertThat(created).isEqualTo(1);
        assertThat(conflicted).isEqualTo(THREADS - 1);
        responses.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .forEach(r -> assertThat(codeOf(r)).isEqualTo("RESOURCE_CONFLICT"));

        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ? AND case_key = 'race'",
                versionId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ C3 导入幂等

    /**
     * 并发同键导入只能产生一个批次。
     *
     * <p>这条用例能成立的机制值得写清楚，否则很容易被"优化"掉：后到的线程在
     * {@code guard.lock()} 里先做了一次普通读（取版本快照），事务的一致性读视图就此固定；
     * 等它排到版本行锁时，先到的线程已经提交，但它看到的仍是旧快照，
     * 因此"与库内已有 caseKey 冲突"的前置检查<b>查不到</b>对方刚写入的样本。
     *
     * <p>真正挡住重复写入的是 {@code dataset_cases} 上的唯一索引：插入撞键抛
     * {@code DuplicateKeyException}，事务回滚并清掉本线程刚写的原文件，
     * 再由 {@code IdempotencyService.execute} 另开事务读回先到者的回执返回。
     * 断言 {@code batchIds} 只有一个、样本数是 2 而不是 8，就是在验证这条链路完整。
     */
    @Test
    void concurrentImportWithSameIdempotencyKeyShouldProduceOneBatch() throws Exception {
        String versionId = createDraftVersion("c3-ds");
        String key = Ulid.newId();
        byte[] content = (caseLine("a") + "\n" + caseLine("b") + "\n").getBytes(StandardCharsets.UTF_8);

        List<ResponseEntity<String>> responses = fire(THREADS, () -> importFile(versionId, key, content));

        // 同一 key 同一文件：无论谁先到，都只能有一个批次，且每个调用都拿到同一份回执
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));

        Set<String> batchIds = new TreeSet<>();
        for (ResponseEntity<String> response : responses) {
            assertThat(data(response).path("importedCount").asInt()).isEqualTo(2);
            batchIds.add(data(response).path("importBatchId").asText());
        }
        assertThat(batchIds).hasSize(1);

        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
        // 2 条而不是 8 条：并发重试不能把样本写多遍
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(2);
        // 只有一个批次 → 只该有一份原文件记录。多出来的 Artifact 就是"有文件、无批次"的孤儿
        assertThat(count("SELECT COUNT(*) FROM artifacts a JOIN import_batches b ON b.file_artifact_id = a.id"
                + " WHERE b.dataset_version_id = ?", versionId)).isEqualTo(1);
    }

    // ------------------------------------------------------------ C4 发布

    @Test
    void concurrentPublishShouldSucceedExactlyOnce() throws Exception {
        String versionId = createDraftVersion("c4-ds");
        post("/api/v1/dataset-versions/" + versionId + "/cases", Ulid.newId(), singleCase("a"));

        List<ResponseEntity<String>> responses = fire(THREADS, () ->
                post("/api/v1/dataset-versions/" + versionId + "/publish", Ulid.newId(), null));

        long published = responses.stream().filter(r -> r.getStatusCode().value() == 200).count();
        long rejected = responses.stream().filter(r -> r.getStatusCode().value() == 409).count();

        assertThat(published).isEqualTo(1);
        assertThat(rejected).isEqualTo(THREADS - 1);
        responses.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .forEach(r -> assertThat(codeOf(r)).isEqualTo("INVALID_STATE_TRANSITION"));

        String hash = jdbc.queryForObject(
                "SELECT content_hash FROM dataset_versions WHERE id = ?", String.class, versionId);
        assertThat(hash).hasSize(64);
        assertThat(jdbc.queryForObject("SELECT status FROM dataset_versions WHERE id = ?",
                String.class, versionId)).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentPlanPublishShouldSucceedExactlyOnce() throws Exception {
        String planId = createPlan("c4-plan");
        String body = "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\""
                + evaluatorId("output.non_empty") + "\",\"executionOrder\":1}]}";
        String versionId = dataId(post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), body));

        List<ResponseEntity<String>> responses = fire(THREADS, () ->
                post("/api/v1/evaluation-plan-versions/" + versionId + "/publish", Ulid.newId(), null));

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 409).count())
                .isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM evaluation_plan_versions WHERE id = ?",
                String.class, versionId)).hasSize(64);
    }

    // ------------------------------------------------------------ 并发原语

    /**
     * 让 {@code task} 同时起跑，收集全部结果。
     *
     * <p>两道闸门：{@code ready} 等到所有线程都进入等待状态，{@code go} 再统一放行。
     * 少任何一道，先启动的线程可能已经跑完，测试就退化成顺序调用。
     */
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
                // 行锁等待最多几十毫秒；30 秒还没回来说明是死锁，让用例失败而不是挂住
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ helpers

    private String createDataset(String prefix) {
        ResponseEntity<String> response = post("/api/v1/datasets", Ulid.newId(),
                "{\"code\":\"" + prefix + "-" + Ulid.newId() + "\",\"name\":\"并发测试\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private String createDraftVersion(String prefix) {
        String datasetId = createDataset(prefix);
        ResponseEntity<String> response = post("/api/v1/datasets/" + datasetId + "/versions",
                Ulid.newId(), "{}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private String createPlan(String prefix) {
        ResponseEntity<String> response = post("/api/v1/evaluation-plans", Ulid.newId(),
                "{\"code\":\"" + prefix + "-" + Ulid.newId() + "\",\"name\":\"并发方案\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private String evaluatorId(String key) {
        return jdbc.queryForObject("SELECT id FROM evaluator_definitions WHERE evaluator_key = ?"
                + " ORDER BY version DESC LIMIT 1", String.class, key);
    }

    private String singleCase(String caseKey) {
        return "{\"caseKey\":\"" + caseKey + "\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"p\"},\"expected\":{\"answer\":\"ok\"}}";
    }

    private String caseLine(String caseKey) {
        return "{\"schemaVersion\":1,\"caseKey\":\"" + caseKey + "\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"p\"},\"expected\":{\"answer\":\"ok\"}}";
    }

    private ResponseEntity<String> importFile(String versionId, String key, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "cases.jsonl";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", key);
        return restTemplate.exchange("/api/v1/dataset-versions/" + versionId + "/cases:import",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }
}
