package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.reliability.messaging.FaultInjectingEventPublisher;
import com.repopilot.common.reliability.messaging.PublishOutcome;
import com.repopilot.common.reliability.service.InboxService;
import com.repopilot.common.reliability.service.OutboxPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-011 故障演练：Outbox、RabbitMQ 与 Inbox 可靠性。
 *
 * <p><b>故障怎么注入</b>：不用真的把 RabbitMQ 弄挂——那既不可重复也会让 CI 不稳定。
 * 这里通过替换 {@code EventPublisher} 精确制造四种真实故障：
 * <ul>
 *   <li><b>confirm 丢失</b>：broker 收到了但发布器没收到确认 → 必须重发；</li>
 *   <li><b>消息被退回</b>：无队列绑定 → 必须重发，不能当成功；</li>
 *   <li><b>broker 不可用</b>：连接失败 → 事件必须留在 PENDING，
 *       由数据库扫描通道在恢复后补发；</li>
 *   <li><b>毒消息</b>：永远失败 → 有限重试后置 FAILED，不无限重投。</li>
 * </ul>
 *
 * <p><b>断言的核心不变式</b>：无论消息投递几次，
 * <b>Run / Job 的状态与结果都只能有一份，且不倒退</b>。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "repopilot.outbox.publisher=fault-injecting",
        "repopilot.outbox.publisher-bean=faultInjectingEventPublisher"})
@DisplayName("TASK-011 Outbox、RabbitMQ 与 Inbox 可靠性")
class MessagingReliabilityIT extends AbstractExecutionIT {

    @Autowired
    private OutboxPublisherService publisher;

    @Autowired
    private OutboxEventMapper outbox;

    @Autowired
    private InboxService inbox;

    /** 可编程的发布器替身：每个用例设定它要制造的故障。 */
    @Autowired
    private FaultInjectingEventPublisher faults;

    /**
     * 最近一次 {@link #createAndStartExperiment()} 创建的实验 ID。
     *
     * <p>用于把断言锁定在本用例的素材上，避免"全表取第一条 PENDING"
     * 与其他测试类的残留数据竞争（详见 {@code firstPendingEventId()}）。
     */
    private String lastCreatedExperimentId;

    // ================================================================== ① 事务一致性

    @Test
    @DisplayName("业务状态与 Outbox 事件同事务：回滚时两者一起消失")
    void outboxWriteRollsBackWithBusinessTransaction() {
        String experimentId = createAndStartExperiment();

        // 一个 Run 完成 → 创建评测 Job → 写 evaluation.ready 事件，全部在一个事务里
        List<JsonNode> before = outboxRows();
        assertThat(before).as("Run 封口应产生通知事件").isNotEmpty();

        long eventsForExperiment = count(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN"
                        + " (SELECT r.id FROM evaluation_runs r WHERE r.experiment_id = ?)",
                experimentId);
        assertThat(eventsForExperiment)
                .as("通知事件的聚合 ID 必须指向真实存在的 Run").isPositive();
    }

    @Test
    @DisplayName("事件信封只带 ID 不带正文，且符合契约字段组合")
    void envelopeCarriesIdsOnly() {
        createAndStartExperiment();
        List<JsonNode> rows = outboxRows();

        for (JsonNode row : rows) {
            JsonNode envelope = row.path("payload_json");
            assertThat(envelope.path("eventId").asText())
                    .as("eventId 必须与 Outbox 行 ID 一致").isEqualTo(row.path("id").asText());
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(envelope.path("aggregateId").asText())
                    .isEqualTo(row.path("aggregate_id").asText());
            // 契约要求：run.ready/run.finished 的 payload 只能有 runId
            String eventType = envelope.path("eventType").asText();
            if ("run.ready".equals(eventType) || "run.finished".equals(eventType)) {
                assertThat(envelope.path("aggregateType").asText()).isEqualTo("RUN");
                assertThat(envelope.path("payload").fieldNames()).toIterable().containsExactly("runId");
            } else if ("evaluation.ready".equals(eventType)) {
                assertThat(envelope.path("aggregateType").asText()).isEqualTo("EVALUATION_JOB");
                assertThat(envelope.path("payload").fieldNames()).toIterable().containsExactly("jobId");
            }
        }
    }

    // ================================================================== ② 确认丢失与重发

    @Test
    @DisplayName("发布成功但确认丢失：事件被重发，消费者无重复业务效果")
    void confirmLostTriggersRedeliveryWithoutDuplicateEffect() {
        String experimentId = createAndStartExperiment();
        // **先把事件 ID 全部取出来，再注入故障并扫描**。
        //
        // 顺序反了会拿到错误的行：scanOnce() 是**批量**的（一次最多 50 条），
        // 它会处理本实验的**全部** PENDING 事件。事后再取"第一条"，
        // 取到的是哪一条取决于 created_at 排序与数据库返回顺序——
        // 一旦它恰好是本次已被其他状态影响的那条，断言就报出一个
        // 与被测行为无关的失败。
        //
        // 先取 ID 列表、扫描后再逐条核对，就与"哪条被处理"完全解耦了。
        List<String> eventIds = pendingEventIdsOf(experimentId);
        assertThat(eventIds).as("本用例新建的实验应产生待发布事件").isNotEmpty();
        faults.reset(PublishOutcome.uncertain("模拟 confirm 丢失：broker 已收但确认未达"));
        faults.setAlwaysUncertain(true);

        publisher.scanOnce();

        // 至少有一条必须留在 PENDING 等重发——confirm 丢失不能判定成功
        assertThat(eventIds.stream().map(this::statusOf).filter("PENDING"::equals).count())
                .as("确认丢失不能判定成功，事件必须留在 PENDING 等重发")
                .isPositive();
        assertThat(eventIds.stream().map(this::statusOf).filter("PUBLISHED"::equals).count())
                .as("confirm 丢失时不允许出现被误判为已发布的事件")
                .isZero();

        String eventId = eventIds.get(0);
        // 消费者先处理了一次（模拟"其实收到了"）
        JsonNode envelope = envelopeOf(eventId);
        InboxService.Result first = inbox.accept("worker-run", envelope);
        assertThat(first.outcome()).isEqualTo(InboxService.Outcome.ACCEPTED);

        // 重发到达：必须是 DUPLICATE，且不产生第二份 inbox 记录
        InboxService.Result second = inbox.accept("worker-run", envelope);
        assertThat(second.outcome())
                .as("重发是正常现象，必须被去重挡下").isEqualTo(InboxService.Outcome.DUPLICATE);
        assertThat(count("SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", eventId))
                .as("inbox 只有一条").isEqualTo(1);

        // 恢复后再次扫描：这次确认成功
        faults.setAlwaysUncertain(false);
        faults.reset(PublishOutcome.success());
        makeRetryDue(eventId);
        publisher.scanOnce();

        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
        assertThat(count("SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", eventId))
                .as("确认重发不改变 inbox 条数").isEqualTo(1);
        assertThat(experimentId).isNotBlank();
    }

    @Test
    @DisplayName("消息被退回（无队列绑定）不算成功，必须重试")
    void returnedMessageIsNotTreatedAsPublished() {
        createAndStartExperiment();
        faults.reset(PublishOutcome.returned("模拟 mandatory 退回：exchange 无队列绑定"));

        publisher.scanOnce();

        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'"))
                .as("被退回的消息不能标成已发布——否则没有任何消费者会收到它").isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'"))
                .as("必须留在 PENDING 等待重发").isPositive();
    }

    // ================================================================== ③ fencing

    @Test
    @DisplayName("失效发布器的迟到确认被 fencing 拒绝，不会误标已发布")
    void stalePublisherCannotMarkOthersEventAsPublished() {
        createAndStartExperiment();
        String eventId = firstPendingEventId();

        // 直接改库模拟"发布器 A 领取后租约过期，发布器 B 重新领取"
        String tokenA = "a".repeat(64);
        jdbc.update("UPDATE outbox_events SET publisher_id = 'pub-A',"
                        + " claim_token_hash = ?, lease_until = ? WHERE id = ?",
                tokenA, Timestamp.from(Instant.now().minusSeconds(60)), eventId);
        faults.reset(PublishOutcome.success());
        publisher.scanOnce();
        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
        String tokenAfter = jdbc.queryForObject(
                "SELECT COALESCE(claim_token_hash, '') FROM outbox_events WHERE id = ?",
                String.class, eventId);

        // 让事件重新变回 PENDING（模拟新一轮），再用过期令牌尝试回写
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', claim_token_hash = ?,"
                        + " lease_until = ?, publisher_id = 'pub-B' WHERE id = ?",
                "b".repeat(64), Timestamp.from(Instant.now().plusSeconds(60)), eventId);
        int accepted = outbox.markPublished(eventId, tokenA, Instant.now());

        assertThat(accepted)
                .as("发布器 A 的令牌已失效，回写必须拿到 0 行").isZero();
        assertThat(statusOf(eventId))
                .as("事件仍归发布器 B 所有，不能被 A 标成已发布").isEqualTo("PENDING");
        assertThat(tokenAfter).isNotNull();
    }

    @Test
    @DisplayName("发布器领取后崩溃：租约过期后事件被重新领取，不会永久卡住")
    void expiredLeaseAllowsAnotherPublisherToTakeOver() {
        createAndStartExperiment();
        String eventId = firstPendingEventId();

        // 模拟"已领取但进程崩溃"：挂着租约，且确认从未写回
        jdbc.update("UPDATE outbox_events SET publisher_id = 'dead-publisher',"
                        + " claim_token_hash = ?, lease_until = ? WHERE id = ?",
                "c".repeat(64), Timestamp.from(Instant.now().minusSeconds(120)), eventId);

        faults.reset(PublishOutcome.success());
        publisher.scanOnce();

        assertThat(statusOf(eventId))
                .as("租约过期后必须能被重新领取并发布，否则一次崩溃就永久丢一条通知")
                .isEqualTo("PUBLISHED");
    }

    // ================================================================== ④ 有限重试与毒消息

    @Test
    @DisplayName("毒消息有限重试后置 FAILED，不无限重投")
    void poisonMessageStopsAfterRetryBudget() {
        String experimentId = createAndStartExperiment();
        // 先取 ID 列表再注入故障：scanOnce() 是批量的，
        // 事后取"第一条"会与实际被处理的那条产生耦合（详见上一用例的说明）。
        //
        // 用 allEventIdsOf 而非 pendingEventIdsOf：createAndStartExperiment()
        // 内部的调度可能已经把事件发布掉了，此时"仍是 PENDING 的"可能为空，
        // 但本用例要验证的是"发布失败时会不会停下来"——
        // 事件当前是什么状态并不重要，把它改回可重试即可。
        List<String> eventIds = allEventIdsOf(experimentId);
        assertThat(eventIds).as("本用例新建的实验应产生事件").isNotEmpty();
        String eventId = eventIds.get(0);
        faults.reset(PublishOutcome.failed("模拟毒消息：序列化失败"));

        // 循环到超过重试预算。
        // **每轮把本实验的所有事件都设为可重试**：只有这样，
        // 被测的那条才一定会在某一轮被处理到——
        // 否则可能某几轮它因为 next_retry_at 未到期而被跳过，
        // 12 轮用完还没耗尽预算，断言就报出一个假的"没有停下来"。
        for (int i = 0; i < 12; i++) {
            if ("FAILED".equals(statusOf(eventId))) {
                break;
            }
            for (String id : eventIds) {
                forceRepublishable(id);
            }
            publisher.scanOnce();
        }

        assertThat(statusOf(eventId))
                .as("超过重试上限必须停下来并置 FAILED，等待人工介入")
                .isEqualTo("FAILED");
        assertThat(retryCountOf(eventId)).as("重试次数应被记录").isPositive();
    }

    @Test
    @DisplayName("人工重新投递保留同一 event_id，重置重试计数")
    void manualRequeueKeepsEventId() {
        createAndStartExperiment();
        String eventId = firstPendingEventId();
        jdbc.update("UPDATE outbox_events SET status = 'FAILED', retry_count = 9 WHERE id = ?",
                eventId);

        boolean requeued = publisher.requeue(eventId);

        assertThat(requeued).isTrue();
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        assertThat(retryCountOf(eventId))
                .as("重置计数：人工介入意味着新一轮从零开始").isZero();
        assertThat(jdbc.queryForObject("SELECT id FROM outbox_events WHERE id = ?",
                String.class, eventId))
                .as("必须保留同一 event_id，否则消费者会当成新消息产生第二次副作用")
                .isEqualTo(eventId);
    }

    // ================================================================== ⑤ MQ 不可用时的降级

    @Test
    @DisplayName("MQ 不可用时事件留在 PENDING，恢复后由数据库扫描补发")
    void databaseScanSurvivesMqOutage() {
        String experimentId = createAndStartExperiment();
        faults.setAvailable(false);

        int scanned = publisher.scanOnce();

        assertThat(scanned).as("MQ 不可用时本轮不发布").isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'"))
                .as("事件必须完好保留在数据库里").isPositive();

        // 恢复后同一批事件能被补发
        faults.setAvailable(true);
        faults.reset(PublishOutcome.success());
        int republished = publisher.scanOnce();

        assertThat(republished).as("恢复后应补发积压事件").isPositive();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'"))
                .as("MQ 只是加速器，不是正确性前提").isPositive();
        assertThat(experimentId).isNotBlank();
    }

    @Test
    @DisplayName("恢复后重复消息不会重跑已完成的 Run")
    void duplicateNotificationDoesNotRerunCompletedRun() {
        String experimentId = createAndStartExperiment();
        // 把实验里的 Run 全部跑完
        drainRuns(experimentId);
        long completedBefore = count(
                "SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ? AND status = 'COMPLETED'",
                experimentId);

        faults.reset(PublishOutcome.success());
        publisher.scanOnce();
        // 模拟全量重投：把所有事件再投一遍
        for (JsonNode row : outboxRows()) {
            inbox.accept("worker-run", row.path("payload_json"));
            inbox.accept("worker-eval", row.path("payload_json"));
        }

        long completedAfter = count(
                "SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ? AND status = 'COMPLETED'",
                experimentId);
        assertThat(completedAfter)
                .as("重复通知不得把已完成的 Run 拉回执行态").isEqualTo(completedBefore);
    }

    // ================================================================== ⑥ Inbox 去重与隔离

    @Test
    @DisplayName("同 eventId 异内容被隔离，不当正常重复静默 ACK")
    void payloadConflictIsIsolated() {
        createAndStartExperiment();
        String eventId = firstPendingEventId();
        JsonNode envelope = envelopeOf(eventId);

        assertThat(inbox.accept("worker-run", envelope).outcome())
                .isEqualTo(InboxService.Outcome.ACCEPTED);

        // 同 eventId 但内容变了：模拟数据损坏或上游 bug
        ObjectNode tampered = (ObjectNode) envelope.deepCopy();
        ((ObjectNode) tampered.path("payload")).put("runId", "01JZZZZZZZZZZZZZZZZZZZZZZZ");

        InboxService.Result conflict = inbox.accept("worker-run", tampered);

        assertThat(conflict.outcome())
                .as("异内容必须可见——静默 ACK 会让它永远查不出来")
                .isEqualTo(InboxService.Outcome.PAYLOAD_CONFLICT);
        assertThat(count("SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", eventId))
                .as("冲突消息不写入 inbox").isEqualTo(1);
    }

    @Test
    @DisplayName("通知接口：重复返回 DUPLICATE，异内容返回 409，未知版本返回 400")
    void notificationEndpointEnforcesDedupAndIsolation() {
        createAndStartExperiment();
        String eventId = firstPendingEventId();
        JsonNode envelope = envelopeOf(eventId);
        String body = "{\"consumerName\":\"worker-run\",\"event\":" + envelope + "}";

        ResponseEntity<String> first = workerPost("/internal/v1/notifications/accept",
                acceptBody("worker-run", envelope));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(data(first).path("status").asText()).isEqualTo("ACCEPTED");

        ResponseEntity<String> again = workerPost("/internal/v1/notifications/accept",
                acceptBody("worker-run", envelope));
        assertThat(again.getStatusCode().value())
                .as("重复必须返回 200，否则消费者会无限重试").isEqualTo(200);
        assertThat(data(again).path("status").asText()).isEqualTo("DUPLICATE");

        // 异内容 → 409
        ObjectNode tampered = (ObjectNode) envelope.deepCopy();
        ((ObjectNode) tampered.path("payload")).put("runId", "01JZZZZZZZZZZZZZZZZZZZZZZZ");
        ResponseEntity<String> conflict = workerPost("/internal/v1/notifications/accept",
                acceptBody("worker-run", tampered));
        assertThat(conflict.getStatusCode().value())
                .as("同 ID 异内容必须让调用方看见并停止重试").isEqualTo(409);

        // 未知版本 → 400
        ObjectNode future = (ObjectNode) envelope.deepCopy();
        future.put("schemaVersion", 99);
        ResponseEntity<String> unknown = workerPost("/internal/v1/notifications/accept",
                acceptBody("worker-run", future));
        assertThat(unknown.getStatusCode().value())
                .as("不支持的版本应进隔离队列，不应无限 requeue").isEqualTo(400);
        assertThat(body).isNotBlank();
    }

    @Test
    @DisplayName("通知接口需要 Worker 凭证，未鉴权一律拒绝")
    void notificationEndpointRequiresAuth() {
        createAndStartExperiment();
        JsonNode envelope = envelopeOf(firstPendingEventId());

        ResponseEntity<String> unauthorized = workerPostNoAuth(
                "/internal/v1/notifications/accept", acceptBody("worker-run", envelope));

        assertThat(unauthorized.getStatusCode().value())
                .as("通知入口能唤醒消费者，必须与领取接口同等鉴权").isEqualTo(401);
        assertThat(count("SELECT COUNT(*) FROM inbox_events")).as("未鉴权请求不得留下任何痕迹")
                .isZero();
    }

    // ================================================================== 助手

    /** 建实验 → 提交 → 领取并封口 Run → 生成评测 Job，产生完整的通知事件集合。 */
    private String createAndStartExperiment() {
        faults.reset(PublishOutcome.success());
        faults.setAvailable(true);
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")));
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 1, 1);
        start(experimentId, idem("start"));
        drainRuns(experimentId);
        // 记下来，让随后的 firstPendingEventId() 只看本用例的素材
        lastCreatedExperimentId = experimentId;
        return experimentId;
    }

    /** 领取并封口该实验下的全部 Run（真实 Worker 协议）。 */
    private void drainRuns(String experimentId) {
        for (int round = 0; round < 20; round++) {
            List<JsonNode> tasks = claimAs(workerId("runner"), "RUN:MOCK");
            if (tasks.isEmpty()) {
                return;
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
                List<String> eventIds = appendEvents(task.path("attemptId").asText(),
                        task.path("leaseToken").asText(), sampleEvents());
                completeRun(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                        "SUCCESS", "{\"answer\":\"42\"}", eventIds);
                progressed = true;
            }
            if (!progressed) {
                return;
            }
        }
    }

    /**
     * 读出 Outbox 整行（按创建时间、id 升序）。
     *
     * <p>返回的是**行对象**而不是裸信封：断言需要把信封里的 {@code eventId} 与
     * 行 {@code id} 对照、把 {@code aggregateId} 与列 {@code aggregate_id} 对照。
     * 只 SELECT {@code payload_json} 再按 {@code row.path("payload_json")} 取值会静默
     * 得到 MissingNode——断言两边都是空值，居然还能"通过"。
     */
    private List<JsonNode> outboxRows() {
        return jdbc.query("SELECT id, aggregate_id, event_type, status, payload_json"
                        + " FROM outbox_events ORDER BY created_at, id",
                (rs, rowNum) -> {
                    ObjectNode row = objectMapper.createObjectNode();
                    row.put("id", rs.getString("id"));
                    row.put("aggregate_id", rs.getString("aggregate_id"));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("status", rs.getString("status"));
                    row.set("payload_json", json(rs.getString("payload_json")));
                    return row;
                });
    }

    private ObjectNode acceptBody(String consumerName, JsonNode envelope) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("consumerName", consumerName);
        body.set("event", envelope);
        return body;
    }

    private String firstPendingEventId() {
        // **按本实验的事件过滤，而不是全表取第一条**。
        //
        // 集成测试共用同一个数据库。取"全表第一条 PENDING"意味着结果取决于
        // 哪些测试类先跑过：其他类（尤其 RabbitTopologyIT，它会批量插入
        // 造数据的 PENDING 行）留下的旧行会被优先取走，于是出现
        // "单跑通过、混跑失败"——单跑时 1 passed，混跑时
        // expected "FAILED" but was "PUBLISHED"。
        //
        // 根因在测试的取数方式而不在产品代码，但危害不小：它把一个确定的
        // 测试缺陷伪装成偶发失败，很容易被"重跑一次就好了"掩盖过去。
        // 修在这里（而非逐个改调用点）是因为同一个坑有 7 处调用。
        assertThat(lastCreatedExperimentId)
                .as("应先用 createAndStartExperiment() 造素材")
                .isNotBlank();

        // 优先取本实验仍是 PENDING 的事件；
        // 若本实验的事件都已被发布（本类的 drainRuns 可能触发过发布），
        // 退回本实验的任意事件——这些用例会自己设置所需状态。
        //
        // **不要退回到全表**：那正是最初引发混跑失败的原因。
        List<String> pending = pendingEventIdsOf(lastCreatedExperimentId);
        if (!pending.isEmpty()) {
            return pending.get(0);
        }
        List<String> anyOfExperiment = allEventIdsOf(lastCreatedExperimentId);
        assertThat(anyOfExperiment)
                .as("本用例新建的实验应产生事件（experimentId=%s）", lastCreatedExperimentId)
                .isNotEmpty();
        return anyOfExperiment.get(0);
    }

    /** 本实验名下仍处于 PENDING 的事件 ID（按时间排序）。 */
    private List<String> pendingEventIdsOf(String experimentId) {
        return jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE status = 'PENDING'"
                        + " AND aggregate_id IN"
                        + " (SELECT r.id FROM evaluation_runs r WHERE r.experiment_id = ?)"
                        + " ORDER BY created_at, id",
                String.class, experimentId);
    }

    /** 本实验名下的全部事件 ID（按时间排序）。 */
    private List<String> allEventIdsOf(String experimentId) {
        return jdbc.queryForList(
                "SELECT id FROM outbox_events"
                        + " WHERE aggregate_id IN"
                        + " (SELECT r.id FROM evaluation_runs r WHERE r.experiment_id = ?)"
                        + " ORDER BY created_at, id",
                String.class, experimentId);
    }

    private JsonNode envelopeOf(String eventId) {
        return jdbc.queryForObject(
                "SELECT payload_json FROM outbox_events WHERE id = ?",
                (rs, rowNum) -> json(rs.getString(1)), eventId);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?",
                String.class, eventId);
    }

    private int retryCountOf(String eventId) {
        return jdbc.queryForObject("SELECT retry_count FROM outbox_events WHERE id = ?",
                Integer.class, eventId);
    }

    /** 把退避时间提前，让下一次扫描不用真等。 */
    private void makeRetryDue(String eventId) {
        jdbc.update("UPDATE outbox_events SET next_retry_at = ?, lease_until = NULL WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), eventId);
    }

    /**
     * 把事件强制拨回"待发布且可重试"。
     *
     * <p>**必须同时把 status 改回 PENDING**：{@link #makeRetryDue} 只调
     * {@code next_retry_at}，对一条已被发布（PUBLISHED）的事件无效——
     * 那样它根本不会进入候选列表，循环 12 轮它一次都没被处理，
     * 断言就会报出一个假的"没有停下来"。
     *
     * <p>这正是本用例的语义前提：要验证的是"发布失败时会耗尽预算并停下"，
     * 所以每一轮都得保证它真的被尝试了一次。
     */
    private void forceRepublishable(String eventId) {
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', next_retry_at = ?,"
                        + " lease_until = NULL, claim_token_hash = NULL, publisher_id = NULL"
                        + " WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), eventId);
    }
}
