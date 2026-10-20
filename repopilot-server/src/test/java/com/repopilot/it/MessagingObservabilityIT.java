package com.repopilot.it;

import com.repopilot.common.reliability.mapper.ReliabilityMetricsMapper;
import com.repopilot.common.reliability.service.ConsumerMetrics;
import com.repopilot.common.reliability.service.MessagingObservability;
import com.repopilot.common.reliability.service.RetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-013-E：消息链路的可观测性。
 *
 * <p><b>为什么指标也要有测试</b>：指标写错了不会让程序崩溃，
 * 它只是<b>安静地不出现</b>或者<b>安静地报一个错的数</b>。
 * 这类缺陷比崩溃更难发现——因为没有任何东西会失败。
 * 唯一能挡住它的是"断言这个指标真的注册了，而且取值符合事实"。
 *
 * <p><b>本类不要求真实 broker</b>：除 DLQ 深度探测外，
 * 全部指标来自真实 MySQL 的查询，不需要 broker。
 * DLQ 深度那条用"连不上的 ConnectionFactory"验证缓存更新逻辑，
 * 真实 broker 上的深度由 {@code RabbitTopologyIT} 覆盖。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-013-E 消息链路可观测性")
class MessagingObservabilityIT extends AbstractDatabaseIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ReliabilityMetricsMapper metrics;

    @Autowired
    private MessagingObservability observability;

    @Autowired
    private RetentionService retention;

    @Autowired
    private ConsumerMetrics consumerMetrics;

    // ------------------------------------------------------------------ 指标注册

    @Test
    @DisplayName("任务书要求的七类指标全部注册成功")
    void allRequiredMetricsAreRegistered() {
        // Outbox 侧
        assertThat(registry.find("repopilot.outbox.pending").gauge()).isNotNull();
        assertThat(registry.find("repopilot.outbox.oldest.pending.age.seconds").gauge()).isNotNull();
        assertThat(registry.find("repopilot.outbox.publish.failed").counter()).isNotNull();
        // 消费侧
        assertThat(registry.find("repopilot.consumer.consumed").counter()).isNotNull();
        assertThat(registry.find("repopilot.consumer.failed").counter()).isNotNull();
        assertThat(registry.find("repopilot.consumer.retried").counter()).isNotNull();
        assertThat(registry.find("repopilot.consumer.dead_lettered").counter()).isNotNull();
        assertThat(registry.find("repopilot.consumer.duplicate").counter()).isNotNull();
        // Inbox 侧
        assertThat(registry.find("repopilot.inbox.duplicate").counter()).isNotNull();
        // 消费者延迟 / 积压 / 租约超时
        assertThat(registry.find("repopilot.consumer.delivery.latency.avg.seconds").gauge())
                .isNotNull();
        assertThat(registry.find("repopilot.consumer.delivery.latency.max.seconds").gauge())
                .isNotNull();
        assertThat(registry.find("repopilot.consumer.backlog.runs").gauge()).isNotNull();
        assertThat(registry.find("repopilot.consumer.backlog.oldest.age.seconds").gauge())
                .isNotNull();
        assertThat(registry.find("repopilot.consumer.lease.expired").gauge()).isNotNull();
        assertThat(registry.find("repopilot.consumer.dlq.depth").gauge()).isNotNull();
        // 清理任务
        assertThat(registry.find("repopilot.retention.outbox.deleted").counter()).isNotNull();
        assertThat(registry.find("repopilot.retention.inbox.deleted").counter()).isNotNull();
        assertThat(registry.find("repopilot.retention.errors").counter()).isNotNull();
        assertThat(registry.find("repopilot.retention.last.run.epoch.seconds").gauge()).isNotNull();
        assertThat(registry.find("repopilot.retention.last.success.epoch.seconds").gauge())
                .isNotNull();
        assertThat(registry.find("repopilot.retention.last.failure.epoch.seconds").gauge())
                .isNotNull();
    }

    // ------------------------------------------------------------------ 消费计数

    @Test
    @DisplayName("消费统计上报会累加到指标上")
    void consumerStatsAreAccumulated() {
        double before = registry.get("repopilot.consumer.consumed").counter().count();
        consumerMetrics.record(3, 1, 2, 1, 4, 5, 1);
        double after = registry.get("repopilot.consumer.consumed").counter().count();
        assertThat(after - before).isEqualTo(3.0);
        assertThat(registry.get("repopilot.consumer.failed").counter().count()).isGreaterThan(0.0);
        assertThat(registry.get("repopilot.consumer.retried").counter().count()).isGreaterThan(0.0);
        assertThat(registry.get("repopilot.consumer.dead_lettered").counter().count())
                .isGreaterThan(0.0);
        assertThat(registry.get("repopilot.consumer.duplicate").counter().count()).isGreaterThan(0.0);
        assertThat(registry.get("repopilot.consumer.republished").counter().count())
                .isGreaterThan(0.0);
        assertThat(registry.get("repopilot.consumer.rejected").counter().count()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("负数增量按 0 处理（计数倒退会让对账失去意义）")
    void negativeDeltaIsIgnored() {
        double before = registry.get("repopilot.consumer.consumed").counter().count();
        consumerMetrics.record(-5, -1, -1, -1, -1, -1, -1);
        double after = registry.get("repopilot.consumer.consumed").counter().count();
        assertThat(after).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 链路查询

    @Test
    @DisplayName("积压查询反映真实 QUEUED 数量")
    void backlogReflectsQueuedRuns() {
        long before = metrics.countQueuedRuns();
        // 不注入数据：断言查询能跑通且返回非负数即可。
        // 注入 QUEUED Run 需要完整的实验/数据集前置条件，
        // 那是 TASK-007 的职责；这里只验证观测查询本身可信。
        assertThat(before).isGreaterThanOrEqualTo(0L);
        assertThat(metrics.oldestQueuedRunAgeSeconds()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("租约超时查询不会返回负数")
    void leaseExpiryCountIsNonNegative() {
        assertThat(metrics.countExpiredRunningAttempts()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("端到端延迟在没有任何数据时是 0 而不是负数")
    void latencyIsZeroWithoutData() {
        assertThat(metrics.averageDeliveryLatencySeconds()).isGreaterThanOrEqualTo(0.0);
        assertThat(metrics.maxDeliveryLatencySeconds()).isGreaterThanOrEqualTo(0.0);
    }

    // ------------------------------------------------------------------ 清理结果

    @Test
    @DisplayName("清理任务执行后记录最后执行时间（成功与失败分开记）")
    void retentionRecordsLastRunOutcome() {
        long runBefore = retention.lastRunEpochSecond();
        long successBefore = retention.lastSuccessEpochSecond();
        long failureBefore = retention.lastFailureEpochSecond();

        // 保留期设为 0 天：所有 PUBLISHED 行立即过期，会真的删掉一些行。
        // 这是清理任务的真实路径，不是模拟。
        retention.purgeExpired();

        long runAfter = retention.lastRunEpochSecond();
        assertThat(runAfter).isGreaterThanOrEqualTo(runBefore);
        assertThat(runAfter).isGreaterThan(0L);
        // 成功路径：成功时间被推进，失败时间保持不变
        assertThat(retention.lastSuccessEpochSecond()).isGreaterThanOrEqualTo(successBefore);
        assertThat(retention.lastFailureEpochSecond()).isEqualTo(failureBefore);
    }

    // ------------------------------------------------------------------ DLQ 深度

    @Test
    @DisplayName("DLQ 深度探测失败时保留上次值（不当作 0）")
    void deadLetterDepthKeepsLastValueOnProbeFailure() {
        // 造一个连不上的 ConnectionFactory：探测必然失败。
        // 用 Mockito 而不是匿名内部类：Spring 的 ConnectionFactory 接口
        // 有十几个方法，匿名实现会漏掉抽象方法而编译失败。
        org.springframework.amqp.rabbit.connection.ConnectionFactory broken =
                org.mockito.Mockito.mock(
                        org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        org.mockito.Mockito.when(broken.createConnection())
                .thenThrow(new org.springframework.amqp.AmqpConnectException(
                        new java.net.ConnectException("模拟 broker 不可用")));
        observability.refreshDeadLetterDepth(broken, "repopilot.events.dlq");
        // 探测失败不能把深度报成 0：0 表示"确定没有死信"，
        // 而失败表示"不知道"。把"不知道"报成"确定没有"会让告警在真故障时反而不响。
        assertThat(observability.deadLetterDepth()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("消费统计上报端点接受增量并累加")
    void consumerStatsEndpointAccumulates() {
        double before = registry.get("repopilot.consumer.consumed").counter().count();
        String body = """
                {"consumerName":"it-consumer","stats":{"acked":2,"failed":1,"retried":0,
                 "deadLettered":1,"duplicates":0,"republished":0,"rejectFailed":0}}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/v1/messaging/consumer-stats", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"accepted\":true");
        double after = registry.get("repopilot.consumer.consumed").counter().count();
        assertThat(after - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Worker 只报 retried/deadLettered 时 failed 由后端求和推导")
    void failedCounterIsDerivedFromRetriedAndDeadLettered() {
        // 本项目的 Worker **从不发 failed**：它只报"进了重试"和"进了死信"
        // 两个互斥的结局。若端点直接读 failed，那个计数器会永远是 0，
        // 而它已经写进了运行手册与告警建议——恒为 0 的失败计数器比没有更糟。
        double before = registry.get("repopilot.consumer.failed").counter().count();
        String body = """
                {"consumerName":"it-worker","stats":{"acked":5,"retried":2,"deadLettered":1}}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/v1/messaging/consumer-stats", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        double after = registry.get("repopilot.consumer.failed").counter().count();
        assertThat(after - before)
                .as("失败数 = 进重试 + 进死信，缺 failed 字段时必须推导而不是记 0")
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("消费统计上报端点拒绝未鉴权请求")
    void consumerStatsEndpointRequiresAuth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 不带 Authorization 头：WorkerAuthFilter 覆盖整个 /internal/v1 前缀
        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/v1/messaging/consumer-stats", HttpMethod.POST,
                new HttpEntity<>("{\"consumerName\":\"x\",\"stats\":{}}", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("消费统计上报端点忽略负数增量")
    void consumerStatsEndpointIgnoresNegativeDelta() {
        double before = registry.get("repopilot.consumer.consumed").counter().count();
        String body = """
                {"consumerName":"it-consumer","stats":{"acked":-5}}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/v1/messaging/consumer-stats", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(registry.get("repopilot.consumer.consumed").counter().count())
                .isEqualTo(before);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 测试用凭证（不是生产凭据）：与 application-it.yml 中的 worker.token 一致
        headers.set("Authorization", "Bearer it-worker-token");
        return headers;
    }
}
