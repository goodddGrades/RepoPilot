package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据集、版本与样本的真实 HTTP + 真实 MySQL 验收（对应手册 I6–I10、I15–I17）。
 *
 * <p>重点不在"接口能返回 200"，而在几条会被并发和习惯绕过的规则：
 * 版本号连续、空版本不能发布、发布后样本不可改、克隆版本摘要与原版本相同。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasetApiIT extends AbstractApiIT {

    @Test
    void duplicateDatasetCodeShouldReturnConflict() {
        String code = "ds-dup-" + Ulid.newId();

        assertThat(post("/api/v1/datasets", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"数据集一\"}").getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> second = post("/api/v1/datasets", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"数据集二\"}");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(second)).isEqualTo("RESOURCE_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM datasets WHERE code = ?", code)).isEqualTo(1);
    }

    @Test
    void versionsShouldBeNumberedContinuously() {
        String datasetId = createDataset("ds-ver-" + Ulid.newId());

        assertThat(createVersion(datasetId).path("version").asInt()).isEqualTo(1);
        assertThat(createVersion(datasetId).path("version").asInt()).isEqualTo(2);
        assertThat(createVersion(datasetId).path("version").asInt()).isEqualTo(3);

        // 列表按版本倒序，前端拿到的最新版本在第一页第一项
        JsonNode page = data(get("/api/v1/datasets/" + datasetId + "/versions"));
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("items").path(0).path("version").asInt()).isEqualTo(3);
    }

    @Test
    void publishingEmptyVersionShouldBeRejected() {
        String datasetId = createDataset("ds-empty-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();

        ResponseEntity<String> response = post("/api/v1/dataset-versions/" + versionId + "/publish",
                Ulid.newId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(response).path("field").asText()).isEqualTo("cases");
        assertThat(count("SELECT COUNT(*) FROM dataset_versions WHERE id = ? AND status = 'PUBLISHED'",
                versionId)).isZero();
    }

    @Test
    void publishedVersionShouldRejectCaseWrites() {
        String datasetId = createDataset("ds-lock-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();
        String caseId = createCase(versionId, "case-1");

        ResponseEntity<String> published = post("/api/v1/dataset-versions/" + versionId + "/publish",
                Ulid.newId(), null);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        assertThat(data(published).path("contentHash").asText()).hasSize(64);

        ResponseEntity<String> created = post("/api/v1/dataset-versions/" + versionId + "/cases",
                Ulid.newId(), caseBody("case-2"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(created)).isEqualTo("INVALID_STATE_TRANSITION");

        ResponseEntity<String> updated = put("/api/v1/dataset-cases/" + caseId, Ulid.newId(),
                caseBody("case-1b"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> deleted = delete("/api/v1/dataset-cases/" + caseId, Ulid.newId());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
    }

    @Test
    void caseKeyShouldBeUniqueWithinVersion() {
        String datasetId = createDataset("ds-key-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();
        createCase(versionId, "same-key");

        ResponseEntity<String> duplicate = post("/api/v1/dataset-versions/" + versionId + "/cases",
                Ulid.newId(), caseBody("same-key"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(duplicate)).isEqualTo("RESOURCE_CONFLICT");
        assertThat(firstError(duplicate).path("field").asText()).isEqualTo("caseKey");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isEqualTo(1);
    }

    @Test
    void updateAndDeleteCaseShouldWorkOnDraft() {
        String datasetId = createDataset("ds-edit-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();
        String caseId = createCase(versionId, "k1");

        ResponseEntity<String> updated = put("/api/v1/dataset-cases/" + caseId, Ulid.newId(),
                "{\"caseKey\":\"k1\",\"taskType\":\"LLM_CALL\",\"input\":{\"prompt\":\"changed\"}}");
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(data(updated).path("taskType").asText()).isEqualTo("LLM_CALL");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE id = ? AND task_type = 'LLM_CALL'",
                caseId)).isEqualTo(1);

        ResponseEntity<String> deleted = delete("/api/v1/dataset-cases/" + caseId, Ulid.newId());
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        assertThat(data(deleted).path("deleted").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE id = ?", caseId)).isZero();
    }

    @Test
    void unknownTaskTypeShouldBeRejected() {
        String datasetId = createDataset("ds-task-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();

        ResponseEntity<String> response = post("/api/v1/dataset-versions/" + versionId + "/cases",
                Ulid.newId(), "{\"caseKey\":\"bad\",\"taskType\":\"CHAT\",\"input\":{}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText()).isEqualTo("body.taskType");
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", versionId))
                .isZero();
    }

    @Test
    void cloneShouldCopyCasesAndProduceSameContentHash() {
        String datasetId = createDataset("ds-clone-" + Ulid.newId());
        String v1 = createVersion(datasetId).path("id").asText();
        createCase(v1, "a");
        createCase(v1, "b");
        String hashV1 = data(post("/api/v1/dataset-versions/" + v1 + "/publish", Ulid.newId(), null))
                .path("contentHash").asText();

        JsonNode cloned = createVersion(datasetId, "{\"cloneFromVersionId\":\"" + v1 + "\"}");
        String v2 = cloned.path("id").asText();
        assertThat(cloned.path("caseCount").asLong()).isEqualTo(2);
        // 草稿没有摘要：提前算出来会让人误以为这版已定稿
        assertThat(cloned.hasNonNull("contentHash")).isFalse();

        String hashV2 = data(post("/api/v1/dataset-versions/" + v2 + "/publish", Ulid.newId(), null))
                .path("contentHash").asText();
        assertThat(hashV2).isEqualTo(hashV1);
        assertThat(count("SELECT COUNT(*) FROM dataset_cases WHERE dataset_version_id = ?", v2)).isEqualTo(2);
    }

    @Test
    void cloneFromAnotherDatasetShouldBeRejected() {
        String datasetA = createDataset("ds-a-" + Ulid.newId());
        String datasetB = createDataset("ds-b-" + Ulid.newId());
        String versionA = createVersion(datasetA).path("id").asText();

        ResponseEntity<String> response = post("/api/v1/datasets/" + datasetB + "/versions", Ulid.newId(),
                "{\"cloneFromVersionId\":\"" + versionA + "\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText()).isEqualTo("cloneFromVersionId");
    }

    @Test
    void archiveShouldKeepContentHashAndBlockFurtherChanges() {
        String datasetId = createDataset("ds-arch-" + Ulid.newId());
        String versionId = createVersion(datasetId).path("id").asText();
        createCase(versionId, "k1");
        String hash = data(post("/api/v1/dataset-versions/" + versionId + "/publish", Ulid.newId(), null))
                .path("contentHash").asText();

        ResponseEntity<String> archived = post("/api/v1/dataset-versions/" + versionId + "/archive",
                Ulid.newId(), null);
        assertThat(archived.getStatusCode().value()).isEqualTo(200);
        assertThat(data(archived).path("status").asText()).isEqualTo("ARCHIVED");

        // 归档不是"重新发布"：摘要与发布时间都必须原样保留
        assertThat(jdbc.queryForObject("SELECT content_hash FROM dataset_versions WHERE id = ?",
                String.class, versionId)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT published_at FROM dataset_versions WHERE id = ?",
                Object.class, versionId)).isNotNull();

        // ARCHIVED 是终态：不能重复发布，也不能回到草稿
        ResponseEntity<String> republish = post("/api/v1/dataset-versions/" + versionId + "/publish",
                Ulid.newId(), null);
        assertThat(republish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void archivedDatasetShouldNotAcceptNewVersion() {
        String datasetId = createDataset("ds-arch2-" + Ulid.newId());

        ResponseEntity<String> patched = patch("/api/v1/datasets/" + datasetId, Ulid.newId(),
                "{\"status\":\"ARCHIVED\"}");
        assertThat(patched.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> created = post("/api/v1/datasets/" + datasetId + "/versions",
                Ulid.newId(), "{}");
        assertThat(created.getStatusCode().value()).isIn(400, 409);
        assertThat(count("SELECT COUNT(*) FROM dataset_versions WHERE dataset_id = ?", datasetId)).isZero();

        ResponseEntity<String> reactivate = patch("/api/v1/datasets/" + datasetId, Ulid.newId(),
                "{\"status\":\"ACTIVE\"}");
        assertThat(reactivate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(reactivate)).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void deletingDatasetCodeChangeShouldBeRejected() {
        String datasetId = createDataset("ds-code-" + Ulid.newId());
        ResponseEntity<String> response = patch("/api/v1/datasets/" + datasetId, Ulid.newId(),
                "{\"code\":\"another-code\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText()).isEqualTo("code");
    }

    // ---------------------------------------------------------------- helpers

    private String createDataset(String code) {
        ResponseEntity<String> response = post("/api/v1/datasets", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"测试数据集\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private JsonNode createVersion(String datasetId) {
        return createVersion(datasetId, "{}");
    }

    private JsonNode createVersion(String datasetId, String body) {
        ResponseEntity<String> response = post("/api/v1/datasets/" + datasetId + "/versions",
                Ulid.newId(), body);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return data(response);
    }

    private String createCase(String versionId, String caseKey) {
        ResponseEntity<String> response = post("/api/v1/dataset-versions/" + versionId + "/cases",
                Ulid.newId(), caseBody(caseKey));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private String caseBody(String caseKey) {
        return "{\"caseKey\":\"" + caseKey + "\",\"taskType\":\"AGENT_TASK\","
                + "\"input\":{\"prompt\":\"fix the bug\"},\"expected\":{\"answer\":\"ok\"}}";
    }
}
