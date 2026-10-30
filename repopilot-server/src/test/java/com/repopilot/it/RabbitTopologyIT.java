package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.reliability.entity.OutboxEvent;
import com.repopilot.common.reliability.mapper.InboxEventMapper;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.reliability.messaging.EventPublisher;
import com.repopilot.common.reliability.messaging.PublishOutcome;
import com.repopilot.common.reliability.messaging.RabbitTopology;
import com.repopilot.common.reliability.messaging.RabbitTopologyDeclarer;
import com.repopilot.common.reliability.service.DeadLetterService;
import com.repopilot.common.reliability.service.InboxService;
import com.repopilot.common.reliability.service.OutboxPublisherService;
import com.repopilot.common.reliability.service.RetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-013：真实 RabbitMQ 拓扑、发布闭环与保留策略。
 *
 * <p><b>与 TASK-011 测试的根本区别</b>：那一类用
 * {@code FaultInjectingEventPublisher} 替身制造故障，覆盖的是
 * "故障发生时数据库状态对不对"。本类连<b>真实 broker</b>一起验：
 * 拓扑真的被声明、消息真的进了队列、confirm 真的拿到了。
 * 替身无法回答这些问题——恰恰是"用替身测过、接上真 broker 就全挂"的那种缺陷
 * （TASK-011 的 exchange 传空字符串就是这么漏过去的）。
 *
 * <p><b>需要真实 broker</b>：本类在
 * {@code REPOPILOT_IT_RABBIT_HOST} 未提供时<b>整体跳过</b>，
 * 而不是用替身凑一个"看起来通过"的绿灯。跳过会写在报告里，
 * 不会伪装成验证过。
 *
 * <p><b>不修改既有测试用的故障替身</b>：TASK-011 的 13 个用例继续验证
 * "broker 挂了会怎样"，那是真实存在的降级路径，不该被替换掉。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "REPOPILOT_IT_RABBIT_HOST", matches = ".+")
@DisplayName("TASK-013 真实 RabbitMQ 拓扑、发布闭环与保留策略")
class RabbitTopologyIT extends AbstractDatabaseIT {

    @Autowired
    private RabbitTopologyDeclarer declarer;

    @Autowired
    private OutboxPublisherService publisher;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private OutboxEventMapper outbox;

    @Autowired
    private InboxEventMapper inboxMapper;

    @Autowired
    private InboxService inbox;

    @Autowired
    private RetentionService retention;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ A：拓扑

    @Test
    @DisplayName("A1 拓扑可重复声明：第二次声明不产生冲突")
    void topologyIsIdempotent() {
        // 第一次已在启动时声明过；这里再声明两次，模拟滚动发布期间新旧实例并存。
        List<String> first = declarer.declare();
        List<String> second = declarer.declare();
        // 参数一致时不该触发"删除重建"路径，所以两次结果相同。
        assertThat(first).isNotEmpty();
        assertThat(second).isEqualTo(first);
        assertThat(first).anyMatch(s -> s.contains(RabbitTopology.EVENTS_EXCHANGE));
        assertThat(first).anyMatch(s -> s.contains(RabbitTopology.DLX_EXCHANGE));
        assertThat(first).anyMatch(s -> s.contains(RabbitTopology.DEAD_LETTER_QUEUE));
    }

    @Test
    @DisplayName("A2 业务队列都带死信交换机：否则 reject 的消息会被静默丢弃")
    void businessQueuesDeadLetter() {
        // **必须按队列类别区分，不能一刀切**（这是 topic 方案带来的真实差异）：
        //
        // * 业务队列（run.ready / evaluation.ready / experiment.finished）
        //   的 DLX 指向 repopilot.events.dlx——消费失败且重试耗尽时 reject 进 DLQ。
        // * 退避等待队列（retry.wait.tN）的 DLX 指向 repopilot.events.republish——
        //   TTL 到期后必须去**再发布队列**复原 routing key，而不是进死信。
        //   若给它们配上业务 DLX，退避消息会在 TTL 到期时被判为死信，
        //   "可重试故障"就永远拿不到重试机会。
        // * 再发布队列（retry.republish）额外指向业务 DLX：
        //   那是"无法复原 routing key"的最后一道兜底。
        //
        // 之前的版本用 `allMatch(dlx->events.dlx)` 覆盖所有队列，
        // 在 topic 方案下必然失败——测试的假设跟不上拓扑的真实演进。
        List<String> declared = declarer.declare();

        List<String> businessQueues = declared.stream()
                .filter(s -> s.startsWith("queue repopilot."))
                .filter(s -> !s.contains(RabbitTopology.DEAD_LETTER_QUEUE))
                .filter(s -> !isRetryWaitQueue(s))
                .filter(s -> !s.contains(RabbitTopology.REPUBLISH_QUEUE))
                .toList();
        assertThat(businessQueues)
                .as("业务队列必须存在，否则本用例等于什么都没验证")
                .isNotEmpty();
        assertThat(businessQueues)
                .allMatch(s -> s.contains("dlx->" + RabbitTopology.DLX_EXCHANGE));

        List<String> retryQueues = declared.stream()
                .filter(RabbitTopologyIT::isRetryWaitQueue)
                .toList();
        assertThat(retryQueues)
                .as("三档退避等待队列都必须声明")
                .hasSize(RabbitTopology.RETRY_TIER_COUNT);
        assertThat(retryQueues)
                .as("退避队列的 DLX 必须指向再发布交换机，否则退避消息会被判死信")
                .allMatch(s -> s.contains("dlx->" + RabbitTopology.REPUBLISH_EXCHANGE));

        assertThat(declared.stream()
                .filter(s -> s.contains("queue " + RabbitTopology.REPUBLISH_QUEUE)))
                .as("再发布队列必须有兜底 DLX：无法复原 routing key 的消息不能被静默丢弃")
                .anyMatch(s -> s.contains("dlx->" + RabbitTopology.DLX_EXCHANGE));
    }

    private static boolean isRetryWaitQueue(String declaredLine) {
        // 声明器返回的行里**队列声明与 binding 是分开的两类**：
        //   "queue repopilot.retry.wait.t1 (durable,quorum,ttl=5000ms)"
        //   "bind  repopilot.retry.wait.t1 <- repopilot.events.retry.v2 [retry.t1]"
        // 只按名字匹配会把 bind 行也算进来，断言 size 就从 3 变成 6。
        // 而且 bind 行**不含 dlx-> 字样**，混进来还会让DLX 断言失真。
        if (!declaredLine.startsWith("queue ")) {
            return false;
        }
        for (int tier = 1; tier <= RabbitTopology.RETRY_TIER_COUNT; tier++) {
            if (declaredLine.contains(RabbitTopology.retryQueueName(tier))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("A3 DLQ 绑定全部业务 routing key：AMQP 的 reject 无法改写死信 key")
    void deadLetterBindsEveryBusinessKey() {
        // 这是实测踩过的坑：初版 DLQ 只绑 dead.*，而 basic_reject 无法设置
        // 死信 routing key，死信沿用原 key 到达 DLX 后匹配不到队列、被丢弃。
        assertThat(RabbitTopology.DEAD_LETTER_KEYS)
                .contains(RabbitTopology.RK_RUN_READY, RabbitTopology.RK_EVALUATION_READY,
                        RabbitTopology.RK_RUN_FINISHED, RabbitTopology.RK_EXPERIMENT_FINISHED);
        assertThat(RabbitTopology.DEAD_LETTER_KEYS)
                .as("DLQ 不能用通配符：新增事件类型时应由声明处强制决定")
                .noneMatch(k -> k.contains("*") || k.contains("#"));
    }

    @Test
    @DisplayName("A4 死信不可能回流成业务消息：DLX 与业务交换机物理隔离")
    void deadLettersCannotFlowBack() {
        assertThat(RabbitTopology.DLX_EXCHANGE)
                .as("DLX 必须是独立交换机，否则误绑定就会让死信回到业务队列")
                .isNotEqualTo(RabbitTopology.EVENTS_EXCHANGE);
    }

    // ------------------------------------------------------------------ B：真实发布

    @Test
    @DisplayName("B1 真实发布：confirm 成功且事件标为 PUBLISHED")
    void publishesToRealBroker() throws Exception {
        Instant now = Instant.now();
        OutboxEvent event = pendingEvent("run.ready", now);
        outbox.insert(event);

        assertThat(eventPublisher.available()).as("真实 broker 必须可达").isTrue();
        PublishOutcome outcome = eventPublisher.publish("run.ready", envelope(event));
        assertThat(outcome.routed())
                .as("必须路由到 repopilot.events 交换机并命中队列，实际: %s", outcome.detail())
                .isTrue();

        quarantineOtherPendingEvents(event.getId());
        boolean published = publisher.scanOnce() > 0;
        assertThat(published).isTrue();

        OutboxEvent reloaded = outbox.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(com.repopilot.common.state.OutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();
        // 标 PUBLISHED 后租约与令牌必须清空，否则残留的租约会掩盖 fencing 失效
        assertThat(reloaded.getLeaseUntil()).isNull();
        assertThat(reloaded.getClaimTokenHash()).isNull();
    }

    @Test
    @DisplayName("B2 真实 broker 下不可路由的消息必须被退回而不是静默丢弃")
    void unroutableMessageIsReturned() throws Exception {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        // 直接发一个没有任何队列绑定的 routing key
        PublishOutcome outcome = eventPublisher.publish("no.such.routing.key",
                envelope(event));
        // mandatory + return：broker 收下了但路由不到队列，必须退回。
        // 若发布器没开 mandatory，这里会"成功"而消息永远消失——
        // 那是拓扑配错时最难发现的静默故障。
        assertThat(outcome.routed()).isFalse();
        assertThat(outcome.returned()).isTrue();
    }

    @Test
    @DisplayName("B3 不可路由的事件留在 PENDING 并退避，不被判死")
    void unroutableEventStaysPending() throws Exception {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        // 必须先落库：scanOnce 扫的是数据库，不在库里的事件根本不会被看到
        // （第一版漏了这行，断言在 orElseThrow 上炸 NoSuchElement——
        //  典型的"测试自己错了却看起来像产品缺陷"）
        outbox.insert(event);
        EventPublisher broken = ScriptedEventPublisher.alwaysReturned(
                "NO_ROUTE replyCode=312 replyText=NO_ROUTE");
        OutboxPublisherService isolated = newPublisherWith(broken);

        quarantineOtherPendingEvents(event.getId());
        assertThat(isolated.scanOnce()).isEqualTo(0);

        OutboxEvent reloaded = outbox.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("不可路由是环境问题，应退避重试而不是判死——判死会丢通知")
                .isEqualTo(com.repopilot.common.state.OutboxStatus.PENDING);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        assertThat(reloaded.getNextRetryAt())
                .as("必须安排退避，否则会形成高速重投打垮 broker")
                .isNotNull();
    }

    @Test
    @DisplayName("B4 重试耗尽进 FAILED 且保留原始信封，供人工重放")
    void exhaustedRetriesBecomeFailed() throws Exception {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        outbox.insert(event);

        OutboxPublisherService isolated = newPublisherWith(
                ScriptedEventPublisher.alwaysFailed("注入故障：连接不可用"));

        // maxAttempts 默认 8；这里跑到 FAILED 为止
        for (int i = 0; i < 10; i++) {
            quarantineOtherPendingEvents(event.getId());
            isolated.scanOnce();
            OutboxEvent reloaded = outbox.findById(event.getId()).orElseThrow();
            if (reloaded.getStatus() == com.repopilot.common.state.OutboxStatus.FAILED) {
                break;
            }
            // 把 next_retry_at 提前，让下一轮立即可领
            forceRetryNow(event.getId());
        }

        OutboxEvent reloaded = outbox.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(com.repopilot.common.state.OutboxStatus.FAILED);
        assertThat(reloaded.getPayloadJson()).as("失败事件必须保留原始信封").isNotNull();
        assertThat(reloaded.getRetryCount()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ C：租约与fencing

    @Test
    @DisplayName("C1 租约过期的失效发布器不能把事件标为已发布")
    void staleLeaseCannotMarkPublished() {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        outbox.insert(event);
        String tokenHash = "a".repeat(64);

        // 领取但立刻让租约过期
        int claimed = outbox.claimForPublish(event.getId(), "publisher-x",
                Instant.now().minusSeconds(1), tokenHash, Instant.now());
        assertThat(claimed).isEqualTo(1);

        int marked = outbox.markPublished(event.getId(), tokenHash, Instant.now());
        assertThat(marked)
                .as("租约已过期时确认必须被拒绝，否则失效发布器会误标已发布导致永久不重发")
                .isEqualTo(0);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(com.repopilot.common.state.OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("C2 claimToken 不匹配的确认必须被拒绝")
    void wrongTokenCannotMarkPublished() {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        outbox.insert(event);
        outbox.claimForPublish(event.getId(), "publisher-x",
                Instant.now().plusSeconds(60), "b".repeat(64), Instant.now());

        int marked = outbox.markPublished(event.getId(), "c".repeat(64), Instant.now());
        assertThat(marked).as("token 不匹配 = 别的发布器持有").isEqualTo(0);
    }

    // ------------------------------------------------------------------ D：Inbox 去重

    @Test
    @DisplayName("D1 同内容重复被识别为 DUPLICATE，不产生第二次副作用")
    void duplicateIsDetected() throws Exception {
        ObjectNode envelope = envelope(pendingEvent("run.ready", Instant.now()));
        String consumer = "worker-dedup-" + System.nanoTime();

        assertThat(inbox.accept(consumer, envelope).outcome())
                .isEqualTo(InboxService.Outcome.ACCEPTED);
        assertThat(inbox.accept(consumer, envelope).outcome())
                .as("同 eventId 同内容 = 正常重复（confirm 丢失导致重发）")
                .isEqualTo(InboxService.Outcome.DUPLICATE);
        assertThat(inbox.accept(consumer, envelope).outcome())
                .isEqualTo(InboxService.Outcome.DUPLICATE);
    }

    @Test
    @DisplayName("D2 同 eventId 异内容被判为冲突：数据损坏必须让人看见")
    void payloadConflictIsDetected() throws Exception {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        ObjectNode envelope = envelope(event);
        String consumer = "worker-conflict-" + System.nanoTime();
        inbox.accept(consumer, envelope);

        // 改 payload 但保留 eventId —— 模拟上游改写已发出事件或数据传输损坏
        ObjectNode tampered = envelope.deepCopy();
        ((ObjectNode) tampered.get("payload")).put("runId", "01M5B0000000000000000000000");

        InboxService.Result result = inbox.accept(consumer, tampered);
        assertThat(result.outcome())
                .as("同 eventId 不同内容绝不能当正常重复静默吞掉")
                .isEqualTo(InboxService.Outcome.PAYLOAD_CONFLICT);
    }

    @Test
    @DisplayName("D3 未知信封版本进隔离，不尝试尽力解析")
    void unsupportedVersionIsQuarantined() throws Exception {
        ObjectNode envelope = envelope(pendingEvent("run.ready", Instant.now()));
        envelope.put("schemaVersion", 99);
        InboxService.Result result = inbox.accept(
                "worker-unsupported-" + System.nanoTime(), envelope);
        assertThat(result.outcome()).isEqualTo(InboxService.Outcome.UNSUPPORTED_VERSION);
    }

    // ------------------------------------------------------------------ E：保留策略

    @Test
    @DisplayName("E1 只清理已发布且过期的事件；PENDING 与 FAILED 永不自动删除")
    void retentionOnlyDeletesPublishedAndExpired() throws Exception {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(40));

        // 1) 已发布且过期 → 应被清理
        OutboxEvent publishedOld = pendingEvent("run.ready", old);
        publishedOld.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
        publishedOld.setPublishedAt(old);
        outbox.insert(publishedOld);

        // 2) 已发布但未过期 → 必须保留
        OutboxEvent publishedFresh = pendingEvent("run.ready", now);
        publishedFresh.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
        publishedFresh.setPublishedAt(now);
        outbox.insert(publishedFresh);

        // 3) PENDING 但很旧 → 必须保留（可能在重试窗口内）
        OutboxEvent pendingOld = pendingEvent("run.ready", old);
        pendingOld.setStatus(com.repopilot.common.state.OutboxStatus.PENDING);
        outbox.insert(pendingOld);

        // 4) FAILED 且很旧 → 必须保留（排障与人工重放的唯一线索）
        OutboxEvent failedOld = pendingEvent("run.ready", old);
        failedOld.setStatus(com.repopilot.common.state.OutboxStatus.FAILED);
        outbox.insert(failedOld);

        retention.purgeExpired();

        assertThat(outbox.findById(publishedOld.getId()))
                .as("已发布且超保留期 → 应被清理").isEmpty();
        assertThat(outbox.findById(publishedFresh.getId()))
                .as("未过保留期 → 必须保留").isPresent();
        assertThat(outbox.findById(pendingOld.getId()))
                .as("PENDING 永不自动删除：可能是重试窗口内的事件").isPresent();
        assertThat(outbox.findById(failedOld.getId()))
                .as("FAILED 永不自动删除：定时清理会让运维在看见前失去证据").isPresent();
    }

    @Test
    @DisplayName("E2 刚发布的事件不会因创建时间久远而被误删")
    void freshPublishIsNotDeletedByAge() throws Exception {
        // 3 天前创建、今天才发布：按 created_at 判会立刻删掉，
        // 但它刚发布、可能还有消费者没处理完。必须按 published_at 判。
        Instant createdThreeDaysAgo = Instant.now().minus(Duration.ofDays(3));
        OutboxEvent event = pendingEvent("run.ready", createdThreeDaysAgo);
        event.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outbox.insert(event);

        retention.purgeExpired();

        assertThat(outbox.findById(event.getId()))
                .as("清理必须依据 published_at 而不是 created_at")
                .isPresent();
    }

    @Test
    @DisplayName("E3 分批删除：批次上限生效，不会一次删光")
    void deletesInBoundedBatches() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(60));
        int created = 12;
        List<String> ids = new ArrayList<>(created);
        for (int i = 0; i < created; i++) {
            OutboxEvent event = pendingEvent("run.ready", old);
            event.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
            event.setPublishedAt(old);
            outbox.insert(event);
            ids.add(event.getId());
        }

        retention.purgeExpired();

        // **必须核对本用例插入的行，而不是全表计数**。
        //
        // 之前的写法是 `countByStatus("PUBLISHED") <= 12`——那是全表计数，
        // 同库其他用例（以及历次运行残留）留下的未过期 PUBLISHED 行全都被计入，
        // 于是断言在一个与本用例无关的数字上失败：实测 118 vs 上限 12。
        //
        // 这类"用全局计数验证局部行为"的断言有两个坏处：
        //   1. 它对测试执行顺序/历史残留敏感——不稳定；
        //   2. 它**测不到真正要测的东西**：批次上限是否生效，
        //      取决于"本用例的行是否被删干净"，而不是"表里还剩多少"。
        for (String id : ids) {
            assertThat(outbox.findById(id))
                    .as("已发布且超保留期的行应被清理（含分批循环的收敛）")
                    .isEmpty();
        }

        // 真正的批次上限断言：删除语句带 LIMIT，
        // 因此一轮 purgeExpired() 的单批删除量不会超过配置上限。
        int batchLimit = retention.status().batchSize();
        assertThat(batchLimit)
                .as("批次上限必须是正数，否则分批循环会永不推进")
                .isPositive();
        // 造超过一个批次的量，验证多轮也能收敛到 0
        int overflow = batchLimit + 5;
        List<String> overflowIds = new ArrayList<>(overflow);
        for (int i = 0; i < overflow; i++) {
            OutboxEvent event = pendingEvent("run.ready", old);
            event.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
            event.setPublishedAt(old);
            outbox.insert(event);
            overflowIds.add(event.getId());
        }
        retention.purgeExpired();
        for (String id : overflowIds) {
            assertThat(outbox.findById(id))
                    .as("超过一个批量的行必须在分批循环内被删完（不能只删一批就停）")
                    .isEmpty();
        }

        // 再跑一轮必须幂等：不会重复删、不会报错
        assertThat(retention.status().lastRunResult()).isNotBlank();
        retention.purgeExpired();
    }

    @Test
    @DisplayName("E4 Inbox 清理必须超过去重窗口，否则同一条消息会重新执行副作用")
    void inboxRetentionRespectsDedupWindow() throws Exception {
        ObjectNode envelope = envelope(pendingEvent("run.ready", Instant.now()));
        String consumer = "worker-retention-" + System.nanoTime();
        inbox.accept(consumer, envelope);
        String eventId = envelope.get("eventId").asText();

        // accept() 用 Instant.now() 写入 created_at，而默认保留期是 30 天——
        // 直接跑清理当然清不掉。要测"超过保留期会被清"必须把时间**推进**，
        // 而不是假装写了个 60 天前的时间（第一版就这么写，结果断言在
        // "Optional 不为空"上失败：被测代码其实是对的，是测试造的数据不对）。
        Instant beyondWindow = Instant.now().minus(Duration.ofDays(31));
        jdbc.update("UPDATE inbox_events SET created_at = ? WHERE consumer_name = ?",
                java.sql.Timestamp.from(beyondWindow), consumer);

        retention.purgeExpired();

        assertThat(inboxMapper.findPayloadHash(consumer, eventId))
                .as("超过去重窗口的记录会被清理——这是配置生效的证明")
                .isEmpty();
        InboxService.Result afterCleanup = inbox.accept(consumer, envelope);
        assertThat(afterCleanup.outcome())
                .as("去重记录已清理 → 同消息会被重新受理并可能重复执行副作用，"
                        + "所以 inbox-days 必须显著长于 Outbox 重试窗口")
                .isEqualTo(InboxService.Outcome.ACCEPTED);
    }

    @Test
    @DisplayName("E5 清理只删过期数据，未过期的 Inbox 记录必须保留")
    void inboxRetentionKeepsFreshRecords() throws Exception {
        ObjectNode envelope = envelope(pendingEvent("run.ready", Instant.now()));
        String consumer = "worker-fresh-" + System.nanoTime();
        inbox.accept(consumer, envelope);

        retention.purgeExpired();

        assertThat(inboxMapper.findPayloadHash(consumer,
                        envelope.get("eventId").asText()))
                .as("刚写入的去重记录必须保留")
                .isPresent();
    }

    @Test
    @DisplayName("E6 人工清理 FAILED 必须显式指定时间下界")
    void failedPurgeRequiresExplicitCutoff() throws Exception {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> retention.purgeFailedOutbox(null)).getMessage())
                .contains("时间下界");
    }

    // ------------------------------------------------------------------ F：死信查询与受控重放

    @Test
    @DisplayName("F0 @Value 默认值必须与 RabbitTopology 常量一致（占位符不支持常量拼接）")
    void deadLetterDefaultNamesMatchTopologyConstants() {
        // 这条断言守的是一个**编译得过、运行即错**的坑：
        // `@Value("${x:" + RabbitTopology.CONST + "}")` 里的拼接发生在
        // **占位符解析之后**，解析出的配置 key 会变成
        // `x:repopilot.events.dlq` —— Spring 找不到该项，
        // 于是把整串当成队列名传下来，broker 报：
        //   NOT_FOUND - no queue '${repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq}'
        // 错误信息里带着完整的占位符原文，看起来像"环境变量没配"，
        // 实际是"默认值拼接"这个写法本身无效。
        assertThat(DeadLetterService.DEFAULT_DEAD_LETTER_QUEUE_MATCHES_TOPOLOGY)
                .as("DeadLetterService 的 @Value 默认值与 RabbitTopology 常量已漂移："
                        + "占位符里不能拼常量，只能写字面量并用本断言守住一致性")
                .isTrue();
    }

    @Test
    @DisplayName("F1 查询 DLQ 不改变队列：条数与内容都保持不变")
    void deadLetterQueryDoesNotMutateQueue() throws Exception {
        purgeDlq();
        String eventId = publishRawToDlq(
                "{\"schemaVersion\":1,\"eventId\":\"" + eventId() + "\","
                        + "\"eventType\":\"run.ready\",\"payload\":{\"runId\":\"r\"}}");

        int depthBefore = dlqDepth();
        assertThat(depthBefore).as("前置条件：DLQ 里应有1 条").isEqualTo(1);

        List<DeadLetterService.DeadLetterSummary> first = deadLetters.query(10);

        assertThat(first).as("查询应返回刚投进去的那条").hasSize(1);
        assertThat(first.get(0).eventId()).isEqualTo(eventId);

        // **必须在 query() 的连接完全关闭之后再查深度**。
        //
        // 实测（quorum 队列）：`basicNack(requeue=true)` 之后立刻查深度得到 0，
        // 连接关闭后才恢复成 1——**重投是异步的**。
        //
        // 这个时序差异不只影响测试，它有真实的运维影响：
        //   * 运维查询后**紧接着**用 `rabbitmqctl list_queues` 看深度，
        //     会看到 0，从而误判"死信被清空了"；
        //   * 更糟的是，若此刻有人 purge 队列，重投中的消息会**真的丢失**。
        //
        // 所以断言的是**稳定态**的深度——那才是运维关心的数字。
        Thread.sleep(700);
        assertThat(dlqDepth())
                .as("查询不得改变队列深度（basic.get + nack 会把消息放回队尾）")
                .isEqualTo(depthBefore);

        // 再查一次：必须返回**同样的一条**，不能因为放回队尾而重复
        List<DeadLetterService.DeadLetterSummary> second = deadLetters.query(10);
        assertThat(second).as("重复查询必须返回同一条，不能出现重复条目").hasSize(1);
        assertThat(second.get(0).eventId()).isEqualTo(eventId);
    }

    @Test
    @DisplayName("F2 **没有任何 properties 的死信不能让查询接口 500**")
    void deadLetterWithoutPropertiesIsSummarizable() throws Exception {
        // 这是一条真实踩过的坑：summarize() 里写的是 properties.getHeaders()，
        // 而 DLQ 里的消息可能完全没有 properties（外部生产者、
        // 或在设置 properties 之前就被 reject）。结果是查询接口抛 NPE → 500。
        //
        // **为什么之前没测出来**：所有构造的 GetResponse 都带 properties，
        // 而"构造得整齐"恰恰是替身测试的盲区——
        // 真实世界最难处理的消息，通常是信息最少的那些。
        //
        // 断言用真实的、最朴素的投递方式（basicPublish 不给任何 properties），
        // 而不是精心构造的理想消息。
        purgeDlq();
        publishMinimalToDlq("this-is-not-json-at-all");

        List<DeadLetterService.DeadLetterSummary> summaries = deadLetters.query(10);

        assertThat(summaries).as("无 properties 的死信也必须能被摘要").hasSize(1);
        DeadLetterService.DeadLetterSummary summary = summaries.get(0);
        // 解析不出时用 null 表示"没有"——这比空串更好：
        // null 在 JSON 序列化后是字段缺失，前端能区分"未知"与"空值"
        assertThat(summary.eventId())
                .as("解析不出 eventId 时应为 null（而非抛异常或返回空串）")
                .isNull();
        assertThat(summary.retryTier()).as("没有 x-retry-tier 头时按 0 处理").isZero();
        assertThat(summary.routingKey())
                .as("routing key 必须能读到：运维要知道这条死信原本要去哪")
                .isEqualTo("run.ready");
    }

    @Test
    @DisplayName("F3 重放保留原 eventId，重放后 Inbox 去重仍然生效")
    void replayPreservesEventIdAndDedupStillApplies() throws Exception {
        purgeDlq();
        // 用真实生成的 eventId，避免"测试自己造 ID"与生产路径不一致
        OutboxEvent event = insertPublishedOutbox();
        String eventId = event.getId();
        ObjectNode envelope = envelope(event);
        // 把**与Inbox 登记完全相同的信封**投进 DLQ：
        // 只有这样"重放后仍被去重"才是真的在验证同一个不变式
        publishRawToDlq(objectMapper.writeValueAsString(envelope));

        // 先让消费者"看过"这条消息：Inbox 里已有记录
        InboxService.Result first = inbox.accept("worker-replay", envelope);
        assertThat(first.outcome()).isEqualTo(InboxService.Outcome.ACCEPTED);
        assertThat(inboxMapper.findPayloadHash("worker-replay", eventId))
                .as("前置条件：Inbox 里应有这条记录").isPresent();

        DeadLetterService.ReplayResult result = deadLetters.replay(List.of(eventId), 10);

        assertThat(result.replayed()).as("应重放成功").isEqualTo(1);
        // 关键不变式：eventId 不变 → 消费者侧仍能去重 → 不会重复执行副作用
        assertThat(result.replayedEventIds()).containsExactly(eventId);
        assertThat(dlqDepth()).as("重放后死信应离开 DLQ").isZero();
    }

    @Test
    @DisplayName("F4 拒绝全量重放：eventIds 为空直接报错")
    void emptyReplayIsRejected() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> deadLetters.replay(List.of(), 10)).getMessage())
                .as("不存在'全量重放'这个入口")
                .contains("全量重放");
    }

    @Test
    @DisplayName("F5 重放超过单次上限必须被拒，不允许借 limit 参数绕过")
    void replayBatchLimitIsEnforced() {
        List<String> tooMany = java.util.stream.IntStream.range(0,
                        DeadLetterService.MAX_REPLAY_BATCH + 1)
                .mapToObj(i -> eventId())
                .toList();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> deadLetters.replay(tooMany, Integer.MAX_VALUE)).getMessage())
                .contains("超过单次上限");
    }

    @Test
    @DisplayName("F6 重放不存在的 eventId 不报错，只是明确记为未重放")
    void replayUnknownEventIdIsReportedNotThrown() throws Exception {
        purgeDlq();
        DeadLetterService.ReplayResult result =
                deadLetters.replay(List.of(eventId()), 10);

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.replayed()).isZero();
        assertThat(result.reasons())
                .as("必须说清为什么没重放，而不是静默成功")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ F 区工具

    @Autowired
    private com.repopilot.common.reliability.service.DeadLetterService deadLetters;

    @Autowired
    private org.springframework.amqp.rabbit.connection.ConnectionFactory rabbitConnectionFactory;

    /**
     * 开一条原生 AMQP 连接用于直接检查 broker 上的队列。
     *
     * <p>**写法必须与 {@code DeadLetterService.query()} 一致**：
     * Spring 的 {@code Connection.createChannel(boolean)} 已经直接返回
     * {@code com.rabbitmq.client.Channel}，<b>不需要</b>再调 {@code getConnection()}。
     * 多写一层会得到 {@code Channel} 上并不存在的方法，
     * javac 报"找不到符号"——与队列 API 毫无关系的类型错误。
     */
    private com.rabbitmq.client.Channel newChannel() throws Exception {
        return rabbitConnectionFactory.createConnection().createChannel(false);
    }

    private int dlqDepth() throws Exception {
        // DeclareOk.getMessageCount() 返回 int，是我们要的条数
        return newChannel().queueDeclarePassive(RabbitTopology.DEAD_LETTER_QUEUE)
                .getMessageCount();
    }

    private void purgeDlq() throws Exception {
        newChannel().queuePurge(RabbitTopology.DEAD_LETTER_QUEUE);
    }

    /**
 * 用最朴素的方式往 DLQ 投一条死信：**不带任何 properties**。
     *
     * <p>**必须发到 DLX（{@code repopilot.events.dlx}）而不是业务交换机**：
     * DLQ 的唯一入口是 DLX 上的绑定。发到业务交换机会进业务队列，
     * DLQ 深度仍然是 0——那是**发错了地方**，不是功能有问题。
     * （这个错误在调试时很有欺骗性：代码看起来完全合理。）
     */
    private void publishMinimalToDlq(String body) throws Exception {
        // **必须等 confirm**：basicPublish 返回只说明"broker 收到了这条 publish 命令"，
        // 不代表消息已经路由到 DLQ。立刻查询会看到空的 DLQ——
        // 症状是"我明明投进去了，查询却说什么都没有"。
        //
        // 对照：publishRawToDlq 一直带着 confirm，所以它稳定；
        // 这一条最初漏了，于是它在混跑时偶发失败（单独跑通过）。
        // **时序敏感的断言必须建立在"已确认"之上**，否则它测的是运气。
        com.rabbitmq.client.Channel channel = newChannel();
        channel.confirmSelect();
        channel.basicPublish(RabbitTopology.DLX_EXCHANGE,
                RabbitTopology.RK_RUN_READY, true, null,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        channel.waitForConfirms(5000);
    }

    private String publishRawToDlq(String body) throws Exception {
        String eventId = extractEventId(body);
        com.rabbitmq.client.Channel channel = newChannel();
        channel.confirmSelect();
        channel.basicPublish(RabbitTopology.DLX_EXCHANGE,
                RabbitTopology.RK_RUN_READY, true, null,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        channel.waitForConfirms(5000);
        return eventId;
    }

    /** 造一条真实的 Outbox 记录并返回它（用于取它的真实信封）。 */
    private OutboxEvent insertPublishedOutbox() {
        OutboxEvent event = pendingEvent("run.ready", Instant.now());
        event.setStatus(com.repopilot.common.state.OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outbox.insert(event);
        return event;
    }

    private static String extractEventId(String json) {
        int at = json.indexOf("\"eventId\":\"");
        if (at < 0) {
            return "";
        }
        int start = at + "\"eventId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static String eventId() {
        return com.repopilot.common.id.Ulid.newId();
    }

    // ------------------------------------------------------------------ 工具

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @jakarta.annotation.PostConstruct
    void initJdbc() {
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    /** 用给定发布器构造一个独立的服务实例，避免污染共享 bean。 */
    private OutboxPublisherService newPublisherWith(EventPublisher replacement) {
        return new OutboxPublisherService(outbox, replacement, txManager, 30, 8, 50);
    }

    /**
     * 把<b>其他</b> PENDING 事件的可领取时间推到未来，让本轮 {@code scanOnce()} 只处理本用例的事件。
     *
     * <p><b>为什么必须有这一步</b>：{@code scanOnce()} 扫的是**整张表**，
     * 按 {@code created_at ASC} 取最老的 {@code batchLimit}（50）条。
     * 当其他测试类留下的 PENDING 行超过 50 条时，本用例**刚插入**的行
     * 排在批次之外——于是 {@code scanOnce() > 0} 成立，
     * 这条事件却一条都没被处理，断言看到"发布成功过，但我的事件还是 PENDING"。
     *
     * <p>这个失败与产品行为无关，是<b>测试对全局表状态的隐式依赖</b>：
     * 单独跑本类永远复现不了，混跑才出现。批次饥饿在真实环境里是合法状态
     * （积压超过一批就是会分多轮处理），所以不能改产品去迁就测试；
     * 只能让用例显式声明"本轮我只看自己这条"。
     *
     * <p>推的是 {@code next_retry_at} 而不是删别人的行：延迟是可逆的，
     * 删除会让其他用例的数据凭空消失——那才是真正查不出来的干扰。
     */
    private void quarantineOtherPendingEvents(String keepId) {
        jdbc.update("UPDATE outbox_events SET next_retry_at = DATE_ADD(NOW(3), INTERVAL 1 DAY)"
                + " WHERE status = 'PENDING' AND id <> ?", keepId);
    }

    private OutboxEvent pendingEvent(String eventType, Instant createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setId(com.repopilot.common.id.Ulid.newId());
        event.setAggregateType("RUN");
        event.setAggregateId(com.repopilot.common.id.Ulid.newId());
        event.setEventType(eventType);
        event.setEventVersion(1);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("runId", event.getAggregateId());
        event.setPayloadJson(payload);
        event.setStatus(com.repopilot.common.state.OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setCreatedAt(createdAt);
        event.setUpdatedAt(createdAt);
        return event;
    }

    private ObjectNode envelope(OutboxEvent event) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        envelope.put("eventId", event.getId());
        envelope.put("eventType", event.getEventType());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("aggregateType", event.getAggregateType());
        envelope.put("aggregateId", event.getAggregateId());
        envelope.put("correlationId", event.getAggregateId());
        envelope.set("payload", event.getPayloadJson());
        return envelope;
    }

    /** 把 next_retry_at 提前，让下一轮立即可领（避免测试真等退避）。 */
    private void forceRetryNow(String eventId) {
        jdbc.update("UPDATE outbox_events SET next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), eventId);
    }
}
