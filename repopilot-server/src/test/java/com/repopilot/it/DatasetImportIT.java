package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSONL 整批导入的验收（对应手册 I18–I22）。
 *
 * <p>断言的落点是"整批原子"这件事本身：坏文件必须一条不写、一个批次不建、一个文件不留；
 * 同一组样本经导入与经逐条创建，发布后的 {@code content_hash} 必须完全相同。
 * 后者是这份实现里最容易出错的地方——两条写入路径只要有一处字段处理不同，摘要就对不上。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasetImportIT extends AbstractApiIT {

    @Autowired
    private StorageProperties storageProperties;

    @Test
    void importShouldWriteCasesArtifactBatchAndFile() throws Exception {
        String versionId = newVersion("imp-ok");
        String content = caseLine("a") + "\n" + caseLine("b") + "\n" + caseLine("c") + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(), bytes, "cases.jsonl");
        assertThat(response.getStatusCode().value()).isEqualTo(201);

        JsonNode receipt = data(response);
        assertThat(receipt.path("versionId").asText()).isEqualTo(versionId);
        assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(receipt.path("importedCount").asInt()).isEqualTo(3);
        assertThat(receipt.path("totalCount").asInt()).isEqualTo(3);
        assertThat(receipt.path("sizeBytes").asLong()).isEqualTo(bytes.length);
        assertThat(receipt.path("sha256").asText()).hasSize(64);

        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE dataset_version_id = ? AND status = 'COMPLETED'",
                versionId)).isEqualTo(1);

        String batchId = receipt.path("importBatchId").asText();
        Map<String, Object> batch = jdbc.queryForMap(
                "SELECT import_type, total_count, success_count, failed_count, duplicate_count, last_line_no, file_artifact_id "
                        + "FROM import_batches WHERE id = ?", batchId);
        assertThat(batch.get("import_type")).isEqualTo("DATASET_CASES");
        assertThat(((Number) batch.get("total_count")).longValue()).isEqualTo(3);
        assertThat(((Number) batch.get("success_count")).longValue()).isEqualTo(3);
        assertThat(((Number) batch.get("failed_count")).longValue()).isZero();
        assertThat(((Number) batch.get("duplicate_count")).longValue()).isZero();
        assertThat(((Number) batch.get("last_line_no")).longValue()).isEqualTo(3);

        Map<String, Object> artifact = jdbc.queryForMap(
                "SELECT artifact_type, run_id, status, content_type, size_bytes, sha256, object_key, storage_provider "
                        + "FROM artifacts WHERE id = ?", batch.get("file_artifact_id"));
        assertThat(artifact.get("artifact_type")).isEqualTo("IMPORT_FILE");
        assertThat(artifact.get("run_id")).isNull();
        assertThat(artifact.get("status")).isEqualTo("READY");
        assertThat(artifact.get("content_type")).isEqualTo("application/x-ndjson");
        assertThat(((Number) artifact.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(artifact.get("sha256")).isEqualTo(receipt.path("sha256").asText());
        assertThat(artifact.get("storage_provider")).isEqualTo("LOCAL");

        // 原文件必须真实存在于配置的根目录下，且目录不在版本库里（target/ 已被 .gitignore 排除）
        Path stored = Path.of(storageProperties.getLocal().getRoot())
                .resolve(String.valueOf(artifact.get("object_key")));
        assertThat(Files.exists(stored)).isTrue();
        assertThat(Files.readAllBytes(stored)).isEqualTo(bytes);
        assertThat(stored.toAbsolutePath().normalize().toString())
                .contains("it-storage");
    }

    @Test
    void invalidLinesShouldRejectWholeBatchWithoutAnyWrite() {
        String versionId = newVersion("imp-bad");
        long artifactsBefore = count("SELECT COUNT(*) FROM artifacts");
        String content = caseLine("a") + "\n"
                + "{\"schemaVersion\":1,\"caseKey\":\"b\",\"taskType\":\"AGENT_TASK\",\"input\":{},\"extra\":1}\n"
                + "{\"schemaVersion\":1,\"caseKey\":\"c\",\"taskType\":\"CHAT\",\"input\":{}}\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(), bytes, "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        java.util.List<Map<String, String>> errors = readErrors(response);
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("field")).isEqualTo("lines[2].extra");
        assertThat(errors.get(1).get("field")).isEqualTo("lines[3].taskType");
        assertThat(errors.get(1).get("message")).startsWith("UNKNOWN_TASK_TYPE");

        // 整批拒绝 = 零写入：没有样本、没有批次、没有 artifact、没有文件
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_batches WHERE dataset_version_id = ?", versionId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM artifacts")).isEqualTo(artifactsBefore);
    }

    @Test
    void duplicateCaseKeyInFileShouldBeRejected() {
        String versionId = newVersion("imp-dup");
        String content = caseLine("same") + "\n" + caseLine("same") + "\n";

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                content.getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(response).path("field").asText()).isEqualTo("lines[2].caseKey");
        assertThat(firstError(response).path("message").asText()).startsWith("DUPLICATE_CASE_KEY_IN_FILE");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId)).isZero();
    }

    @Test
    void conflictWithExistingCaseShouldReturn409AndWriteNothing() {
        String versionId = newVersion("imp-conflict");
        post("/api/v1/dataset-versions/" + versionId + "/cases", Ulid.newId(), singleCase("existing"));

        String content = caseLine("existing") + "\n" + caseLine("fresh") + "\n";
        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                content.getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(response)).isEqualTo("RESOURCE_CONFLICT");
        assertThat(firstError(response).path("field").asText()).isEqualTo("lines[1].caseKey");
        assertThat(firstError(response).path("message").asText()).startsWith("CASE_KEY_CONFLICT");

        // 不覆盖、不合并、不跳过：fresh 也不许写进去
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ? AND case_key = 'fresh'",
                versionId)).isZero();
    }

    @Test
    void importToPublishedVersionShouldBeRejected() {
        String versionId = newVersion("imp-pub");
        post("/api/v1/dataset-versions/" + versionId + "/cases", Ulid.newId(), singleCase("a"));
        assertThat(post("/api/v1/dataset-versions/" + versionId + "/publish", Ulid.newId(), null)
                .getStatusCode().value()).isEqualTo(200);

        String content = caseLine("b") + "\n";
        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                content.getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(response)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
    }

    @Test
    void importAndSingleCreateShouldProduceSameContentHash() {
        // 导入版本：文件内按 c、a、b 顺序
        String viaImport = newVersion("hash-imp");
        String imported = caseLine("c") + "\n" + caseLine("a") + "\n" + caseLine("b") + "\n";
        assertThat(importFile(viaImport, Ulid.newId(), imported.getBytes(StandardCharsets.UTF_8), "cases.jsonl")
                .getStatusCode().value()).isEqualTo(201);
        String hashImport = publishAndGetHash(viaImport);

        // 逐条版本：换个顺序逐条创建
        String viaCreate = newVersion("hash-create");
        for (String key : new String[]{"b", "c", "a"}) {
            assertThat(post("/api/v1/dataset-versions/" + viaCreate + "/cases", Ulid.newId(), singleCase(key))
                    .getStatusCode().value()).isEqualTo(201);
        }
        String hashCreate = publishAndGetHash(viaCreate);

        assertThat(hashImport).hasSize(64);
        assertThat(hashCreate).isEqualTo(hashImport);
    }

    @Test
    void sameKeySameFileShouldReplayAndDifferentFileShouldConflict() {
        String versionId = newVersion("imp-idem");
        String key = Ulid.newId();
        byte[] first = (caseLine("a") + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] second = (caseLine("b") + "\n").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> initial = importFile(versionId, key, first, "cases.jsonl");
        assertThat(initial.getStatusCode().value()).isEqualTo(201);
        String batchId = data(initial).path("importBatchId").asText();

        ResponseEntity<String> replay = importFile(versionId, key, first, "cases.jsonl");
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(data(replay).path("importBatchId").asText()).isEqualTo(batchId);
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);

        ResponseEntity<String> conflict = importFile(versionId, key, second, "cases.jsonl");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
    }

    @Test
    void overlongLineShouldBeRejectedWith413() {
        String versionId = newVersion("imp-long");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":1,\"caseKey\":\"big\",\"taskType\":\"AGENT_TASK\",\"input\":{\"prompt\":\"");
        sb.append("x".repeat(70 * 1024));
        sb.append("\"}}\n");

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                sb.toString().getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(codeOf(response)).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId)).isZero();
    }

    @Test
    void bomBlankLinesAndCrlfShouldBeAccepted() {
        String versionId = newVersion("imp-bom");
        String content = "\uFEFF" + caseLine("a") + "\r\n"
                + "   \r\n"
                + caseLine("b") + "\r\n";

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                content.getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(data(response).path("importedCount").asInt()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(2);
    }

    @Test
    void errorLineNumbersShouldCountBlankLines() {
        String versionId = newVersion("imp-line");
        // 第 1 行空、第 2 行合法、第 3 行空、第 4 行缺少 caseKey
        String content = "\n" + caseLine("a") + "\n\n"
                + "{\"schemaVersion\":1,\"taskType\":\"AGENT_TASK\",\"input\":{}}\n";

        ResponseEntity<String> response = importFile(versionId, Ulid.newId(),
                content.getBytes(StandardCharsets.UTF_8), "cases.jsonl");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 行号必须是编辑器里能直接跳过去的物理行号，而不是"第几个非空行"
        assertThat(firstError(response).path("field").asText()).isEqualTo("lines[4].caseKey");
    }

    // ---------------------------------------------------------------- helpers

    private String newVersion(String prefix) {
        ResponseEntity<String> dataset = post("/api/v1/datasets", Ulid.newId(),
                "{\"code\":\"" + prefix + "-" + Ulid.newId() + "\",\"name\":\"导入测试\"}");
        assertThat(dataset.getStatusCode().value()).isEqualTo(201);
        String datasetId = dataId(dataset);

        ResponseEntity<String> version = post("/api/v1/datasets/" + datasetId + "/versions",
                Ulid.newId(), "{}");
        assertThat(version.getStatusCode().value()).isEqualTo(201);
        return dataId(version);
    }

    private String publishAndGetHash(String versionId) {
        ResponseEntity<String> published = post("/api/v1/dataset-versions/" + versionId + "/publish",
                Ulid.newId(), null);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        return data(published).path("contentHash").asText();
    }

    private ResponseEntity<String> importFile(String versionId, String key, byte[] content, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", key);
        return restTemplate.exchange("/api/v1/dataset-versions/" + versionId + "/cases:import",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /** 与 {@code caseLine} 内容等价，用于"逐条创建"路径——两条路径必须写出同样的样本。 */
    private String singleCase(String caseKey) {
        return "{\"caseKey\":\"" + caseKey + "\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"p\"},\"expected\":{\"answer\":\"ok\"}}";
    }

    private String caseLine(String caseKey) {
        return "{\"schemaVersion\":1,\"caseKey\":\"" + caseKey + "\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"p\"},\"expected\":{\"answer\":\"ok\"}}";
    }

    /** 读 errors 数组；断言字段顺序时比树遍历更直观。 */
    private java.util.List<Map<String, String>> readErrors(ResponseEntity<String> response) {
        try {
            java.util.List<Map<String, String>> list = new java.util.ArrayList<>();
            for (JsonNode node : objectMapper.readTree(response.getBody()).path("errors")) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("field", node.path("field").asText());
                item.put("message", node.path("message").asText());
                list.add(item);
            }
            return list;
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }
}
