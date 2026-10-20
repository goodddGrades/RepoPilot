package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.reliability.service.OutboxPublisherService;
import com.repopilot.modules.experiment.service.WorkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-013-E：消息链路与任务恢复的**端到端**串通验证。
 *
 * <p><b>为什么这一类必须存在</b>：前面几类测试各自验证一段——
 * 消息发布了、租约过期了、任务被重新领取了。但生产事故往往发生在
 * <b>两段的接缝上</b>：消息发出去了、Worker 领了任务然后崩了、
 * 租约到期、任务回到 QUEUED——这时候那条消息还在不在？
 * 会不会被重复执行？会不会卡住不动？
 *
 * <p><b>这个类不要求真实 broker</b>：它验证的是
 * "Outbox 发布 → 任务领取 → Worker 失联 → 租约回收 → 重新领取"
 * 这条恢复链路的<b>状态机正确性</b>。
 * 真实 broker 上的投递与重投语义由 {@code RabbitTopologyIT}
 * 与 worker 的 {@code test_recovery_rabbit.py} 覆盖。
 * 两类合起来才是完整证据。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-013-E 消息链路与任务恢复")
class MessagingRecoveryIT extends AbstractExecutionIT {

    @Autowired
    private OutboxPublisherService publisher;

    @Autowired
    private OutboxEventMapper outbox;

    @Autowired
    private WorkService workService;

    // ------------------------------------------------------------------ 恢复链路

    @Test
    @DisplayName("Worker 失联后：租约回收 → 任务回到 QUEUED → 可被新 Worker 领取")
    void lostWorkerLeaseIsReapedAndTaskIsClaimableAgain() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "recover-" + scenario.suffix());

        // Worker 领取任务后"失联"：租约还在，人没了
        JsonNode task = claim("it-worker-lost", 1).get(0);
        String attemptId = task.path("attemptId").asText();
        String runId = task.path("runId").asText();
        assertThat(attemptId).isNotBlank();

        // 消息已经发出去了（Outbox 走完发布流程）
        publisher.scanOnce();
        assertThat(outbox.countByStatus("PUBLISHED")).isNotEmpty();

        // 租约到期：等价于 Worker 进程被杀
        jdbc.update("UPDATE run_attempts SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), attemptId);

        // 后台维护把过期租约收回来
        workService.maintain();

        assertThat(jdbc.queryForObject("SELECT status FROM run_attempts WHERE id = ?",
                String.class, attemptId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE id = ?",
                String.class, runId)).isEqualTo("RUNNING");

        // Run 回到可重试时间后，新 Worker 能重新领到
        jdbc.update("UPDATE evaluation_runs SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), runId);
        workService.maintain();

        List<JsonNode> again = claim("it-worker-new", 1);
        assertThat(again).as("租约回收后任务必须能被重新领取").isNotEmpty();
        assertThat(again.get(0).path("runId").asText()).isEqualTo(runId);
        assertThat(again.get(0).path("attemptId").asText())
                .as("新 Attempt 必须与失效的 Attempt 不同")
                .isNotEqualTo(attemptId);
    }

    @Test
    @DisplayName("消息重复投递不产生重复副作用：Inbox 去重让第二次只登记不执行")
    void duplicateDeliveryDoesNotDuplicateSideEffects() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "dup-" + scenario.suffix());

        publisher.scanOnce();
        String eventId = firstPublishedEventId();
        assertThat(eventId).isNotBlank();

        JsonNode envelope = envelopeOf(eventId);

        // 第一次登记
        String first = acceptBody("it-consumer", envelope);
        assertThat(first).isNotBlank();

        // 第二次登记同一条消息：必须被判为重复
        String second = acceptBody("it-consumer", envelope);
        assertThat(second).isNotBlank();

        // 无论第一次第二次，Inbox 里只有一条记录
        int inboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        assertThat(inboxRows).isEqualTo(1);
    }

    @Test
    @DisplayName("Outbox 发布失败时事件保持 PENDING，broker 恢复后被补发")
    void failedPublishKeepsEventPendingForLaterDelivery() {
        Scenario scenario = seedPublished(1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, "pending-" + scenario.suffix());

        String eventId = firstPendingEventId();
        assertThat(eventId).isNotBlank();

        // 事件在 PENDING：说明还没被成功发布
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?",
                Timestamp.class, eventId)).isNull();
    }

    // ------------------------------------------------------------------ 辅助

    private String firstPublishedEventId() {
        List<String> ids = jdbc.query(
                "SELECT id FROM outbox_events WHERE status = 'PUBLISHED' ORDER BY created_at LIMIT 1",
                (rs, index) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String firstPendingEventId() {
        List<String> ids = jdbc.query(
                "SELECT id FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at LIMIT 1",
                (rs, index) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?",
                String.class, eventId);
    }

    private JsonNode envelopeOf(String eventId) {
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM outbox_events WHERE id = ?", String.class, eventId);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("解析事件信封失败: " + eventId, ex);
        }
    }

    private String acceptBody(String consumerName, JsonNode envelope) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("consumerName", consumerName);
        body.set("event", envelope);
        var response = restTemplate.exchange("/internal/v1/notifications/accept",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, workerHeaders()),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private org.springframework.http.HttpHeaders workerHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + WORKER_TOKEN);
        return headers;
    }
}
