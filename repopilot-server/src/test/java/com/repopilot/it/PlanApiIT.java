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
 * 算子注册清单与评测方案的验收（对应手册 I11–I12）。
 *
 * <p>两条最容易出问题的规则：算子清单必须幂等导入（重启不会变成 12 条），
 * 方案发布时引用的算子必须已发布（否则批次会引用一个还没有实现的算子）。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanApiIT extends AbstractApiIT {

    @Test
    void seedCatalogShouldBeImportedExactlyOnce() {
        ResponseEntity<String> response = get("/api/v1/evaluators?pageSize=100&status=PUBLISHED");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode page = data(response);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(page.path("items").toString()).contains("output.non_empty", "answer.exact_match",
                "trace.tool_error", "cost.budget", "test.command", "judge.rubric");

        // 清单导入是幂等的：同一个 (key, version) 只允许一行
        assertThat(count("SELECT COUNT(*) FROM evaluator_definitions WHERE evaluator_key = 'output.non_empty'"
                + " AND version = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM evaluator_definitions WHERE evaluator_key = 'output.non_empty'"))
                .isEqualTo(1);
    }

    @Test
    void evaluatorFiltersShouldWork() {
        JsonNode byType = data(get("/api/v1/evaluators?type=LLM_JUDGE"));
        assertThat(byType.path("total").asLong()).isEqualTo(1);
        assertThat(byType.path("items").path(0).path("evaluatorKey").asText()).isEqualTo("judge.rubric");

        ResponseEntity<String> badType = get("/api/v1/evaluators?type=NOT_A_TYPE");
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(badType).path("field").asText()).isEqualTo("type");
    }

    @Test
    void duplicatePlanCodeShouldReturnConflict() {
        String code = "plan-" + Ulid.newId();
        assertThat(post("/api/v1/evaluation-plans", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"方案一\"}").getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> second = post("/api/v1/evaluation-plans", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"方案二\"}");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(second)).isEqualTo("RESOURCE_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM evaluation_plans WHERE code = ?", code)).isEqualTo(1);
    }

    @Test
    void planVersionsShouldBeNumberedContinuously() {
        String planId = createPlan("plan-ver-" + Ulid.newId());
        String first = evaluatorId("output.non_empty");
        String second = evaluatorId("trace.tool_error");

        assertThat(createVersion(planId, items(first, second)).path("version").asInt()).isEqualTo(1);
        assertThat(createVersion(planId, items(first, second)).path("version").asInt()).isEqualTo(2);
        assertThat(createVersion(planId, items(first, second)).path("version").asInt()).isEqualTo(3);

        JsonNode page = data(get("/api/v1/evaluation-plans/" + planId + "/versions"));
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("items").path(0).path("version").asInt()).isEqualTo(3);
        // 版本列表里就要带 items，否则前端要为每个版本再发一次请求
        assertThat(page.path("items").path(0).path("items").size()).isEqualTo(2);
    }

    @Test
    void duplicateEvaluatorShouldBeRejected() {
        String planId = createPlan("plan-dup-" + Ulid.newId());
        String first = evaluatorId("output.non_empty");

        ResponseEntity<String> response = post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), versionBody(items(first, first)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText())
                .isEqualTo("items[1].evaluatorDefinitionId");
        assertThat(count("SELECT COUNT(*) FROM evaluation_plan_versions WHERE plan_id = ?", planId)).isZero();
    }

    @Test
    void duplicateExecutionOrderShouldBeRejected() {
        String planId = createPlan("plan-order-" + Ulid.newId());
        String body = "{\"config\":{},\"items\":["
                + "{\"evaluatorDefinitionId\":\"" + evaluatorId("output.non_empty") + "\",\"executionOrder\":1},"
                + "{\"evaluatorDefinitionId\":\"" + evaluatorId("trace.tool_error") + "\",\"executionOrder\":1}]}";

        ResponseEntity<String> response = post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText()).isEqualTo("items[1].executionOrder");
    }

    @Test
    void unknownEvaluatorShouldBeRejected() {
        String planId = createPlan("plan-unknown-" + Ulid.newId());
        String body = "{\"items\":[{\"evaluatorDefinitionId\":\"" + Ulid.newId() + "\"}]}";

        ResponseEntity<String> response = post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText())
                .isEqualTo("items[0].evaluatorDefinitionId");
    }

    @Test
    void missingItemsShouldBeRejected() {
        String planId = createPlan("plan-noitems-" + Ulid.newId());
        ResponseEntity<String> response = post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), "{\"config\":{}}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstError(response).path("field").asText()).isEqualTo("items");
    }

    @Test
    void publishShouldRejectDraftEvaluator() {
        String draftEvaluatorId = insertEvaluator("plan-draft-" + Ulid.newId(), "DRAFT");
        String planId = createPlan("plan-draft-" + Ulid.newId());
        String versionId = createVersion(planId, items(draftEvaluatorId)).path("id").asText();

        ResponseEntity<String> response = post("/api/v1/evaluation-plan-versions/" + versionId + "/publish",
                Ulid.newId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(codeOf(response)).isEqualTo("VALIDATION_ERROR");
        assertThat(firstError(response).path("field").asText())
                .isEqualTo("items[0].evaluatorDefinitionId");
        assertThat(firstError(response).path("message").asText()).contains("算子未发布");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_plan_versions WHERE id = ?",
                String.class, versionId)).isEqualTo("DRAFT");
    }

    @Test
    void publishedPlanVersionMustNotBeModifiable() {
        String planId = createPlan("plan-lock-" + Ulid.newId());
        String versionId = createVersion(planId,
                items(evaluatorId("output.non_empty"))).path("id").asText();

        ResponseEntity<String> published = post("/api/v1/evaluation-plan-versions/" + versionId + "/publish",
                Ulid.newId(), null);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        assertThat(data(published).path("contentHash").asText()).hasSize(64);
        assertThat(data(published).path("items").size()).isEqualTo(1);

        ResponseEntity<String> replaced = put("/api/v1/evaluation-plan-versions/" + versionId, Ulid.newId(),
                versionBody(items(evaluatorId("trace.tool_error"))));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(replaced)).isEqualTo("INVALID_STATE_TRANSITION");

        // 发布后 items 与摘要都必须保持不变
        assertThat(count("SELECT COUNT(*) FROM evaluation_plan_items WHERE plan_version_id = ?", versionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM evaluation_plan_versions WHERE id = ?",
                String.class, versionId)).isNotNull();
    }

    @Test
    void replaceDraftShouldSwapItemsWithoutBumpingVersion() {
        String planId = createPlan("plan-replace-" + Ulid.newId());
        String first = evaluatorId("output.non_empty");
        String second = evaluatorId("trace.tool_error");
        String versionId = createVersion(planId, items(first, second)).path("id").asText();

        ResponseEntity<String> replaced = put("/api/v1/evaluation-plan-versions/" + versionId, Ulid.newId(),
                versionBody(items(second)));
        assertThat(replaced.getStatusCode().value()).isEqualTo(200);

        JsonNode view = data(replaced);
        assertThat(view.path("version").asInt()).isEqualTo(1);
        assertThat(view.path("items").size()).isEqualTo(1);
        assertThat(view.path("items").path(0).path("evaluatorDefinitionId").asText()).isEqualTo(second);
        assertThat(count("SELECT COUNT(*) FROM evaluation_plan_items WHERE plan_version_id = ?", versionId))
                .isEqualTo(1);
    }

    @Test
    void archiveShouldKeepContentHashAndRejectRepublish() {
        String planId = createPlan("plan-arch-" + Ulid.newId());
        String versionId = createVersion(planId,
                items(evaluatorId("output.non_empty"))).path("id").asText();
        String hash = data(post("/api/v1/evaluation-plan-versions/" + versionId + "/publish",
                Ulid.newId(), null)).path("contentHash").asText();

        ResponseEntity<String> archived = post("/api/v1/evaluation-plan-versions/" + versionId + "/archive",
                Ulid.newId(), null);
        assertThat(archived.getStatusCode().value()).isEqualTo(200);
        assertThat(data(archived).path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT content_hash FROM evaluation_plan_versions WHERE id = ?",
                String.class, versionId)).isEqualTo(hash);

        ResponseEntity<String> republish = post("/api/v1/evaluation-plan-versions/" + versionId + "/publish",
                Ulid.newId(), null);
        assertThat(republish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void samePlanVersionContentShouldProduceSameHash() {
        String planId = createPlan("plan-hash-" + Ulid.newId());
        String first = evaluatorId("output.non_empty");
        String second = evaluatorId("answer.exact_match");

        // 两次的数组书写顺序不同，但 (算子, 顺序) 完全一致：摘要按 executionOrder 排序后计算
        String v1 = createVersion(planId,
                "[" + item(first, 1) + "," + item(second, 2) + "]").path("id").asText();
        String v2 = createVersion(planId,
                "[" + item(second, 2) + "," + item(first, 1) + "]").path("id").asText();

        String hashV1 = data(post("/api/v1/evaluation-plan-versions/" + v1 + "/publish", Ulid.newId(), null))
                .path("contentHash").asText();
        String hashV2 = data(post("/api/v1/evaluation-plan-versions/" + v2 + "/publish", Ulid.newId(), null))
                .path("contentHash").asText();

        assertThat(hashV2).isEqualTo(hashV1);
    }

    // ---------------------------------------------------------------- helpers

    private String createPlan(String code) {
        ResponseEntity<String> response = post("/api/v1/evaluation-plans", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"测试方案\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private JsonNode createVersion(String planId, String itemsJson) {
        ResponseEntity<String> response = post("/api/v1/evaluation-plans/" + planId + "/versions",
                Ulid.newId(), versionBody(itemsJson));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return data(response);
    }

    private String versionBody(String itemsJson) {
        return "{\"config\":{\"gate\":{\"maxRegressions\":0}},\"items\":" + itemsJson + "}";
    }

    private String items(String... evaluatorIds) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < evaluatorIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(item(evaluatorIds[i], i + 1));
        }
        return sb.append(']').toString();
    }

    private String item(String evaluatorDefinitionId, int executionOrder) {
        return "{\"evaluatorDefinitionId\":\"" + evaluatorDefinitionId
                + "\",\"executionOrder\":" + executionOrder + "}";
    }

    private String evaluatorId(String key) {
        return jdbc.queryForObject("SELECT id FROM evaluator_definitions WHERE evaluator_key = ?"
                + " ORDER BY version DESC LIMIT 1", String.class, key);
    }

    private String insertEvaluator(String key, String status) {
        String id = Ulid.newId();
        jdbc.update("INSERT INTO evaluator_definitions (id, evaluator_key, version, display_name, evaluator_type,"
                        + " implementation_ref, input_schema_json, output_schema_json, status, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))",
                id, key, 1, "未发布算子", "RULE", "builtin://evaluators/unpublished", "{}", "{}", status);
        return id;
    }
}
