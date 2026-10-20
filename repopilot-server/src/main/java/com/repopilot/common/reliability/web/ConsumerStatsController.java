package com.repopilot.common.reliability.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.reliability.service.ConsumerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.repopilot.common.web.Responses;

import java.util.Map;

/**
 * 消费统计上报入口（{@code POST /internal/v1/messaging/consumer-stats}）。
 *
 * <p><b>为什么需要这个接口</b>：消费结果的真��只在消费者进程里。
 * 一条被 reject 进死信的消息，在 {@code outbox_events} 里仍然是
 * {@code PUBLISHED}（发布确实成功了），在 {@code inbox_events} 里也可能没有记录
 * （它压根没走到登记那一步）。后端数据库<b>无法</b>回答
 * "这一小时消费了多少条、失败了多少条、进了多少死信"。
 *
 * <p><b>只累加计数，不改任何业务状态</b>：这是观测端点，
 * 碰业务状态就意味着排障动作会改变被排障的对象。
 *
 * <p><b>为什么不用"上报累计值"</b>：累计值在消费者进程重启后会归零，
 * 后端累加就会把计数<b>倒退</b>。一个会倒退的计数器比没有计数器更危险，
 * 因为它看起来是对的。约定：<b>上报增量</b>。
 *
 * <p>鉴权复用 {@code WorkerAuthFilter}（覆盖整个 {@code /internal/v1} 前缀）。
 */
@RestController
@RequestMapping("/internal/v1/messaging")
public class ConsumerStatsController {

    private final ConsumerMetrics metrics;

    public ConsumerStatsController(ConsumerMetrics metrics) {
        this.metrics = metrics;
    }

    /** 请求正文：{@code {consumerName, stats}}。 */
    public record ConsumerStatsRequest(String consumerName, Map<String, Integer> stats) {
    }

    /** 响应：{@code {accepted}}。 */
    public record ConsumerStatsResult(boolean accepted) {
    }

    @PostMapping("/consumer-stats")
    public ResponseEntity<ApiResponse<ConsumerStatsResult>> report(
            @RequestBody ConsumerStatsRequest request) {
        if (request.consumerName() == null || request.consumerName().isBlank()) {
            // 不做校验失败：上报是尽力而为的观测通道，
            // 缺 consumerName 时按空名照样累加——丢掉一批计数，
            // 比因为一个名字字段把整个上报通道卡死要好。
            return Responses.ok(new ConsumerStatsResult(false));
        }
        Map<String, Integer> stats = request.stats() == null ? Map.of() : request.stats();
        // failed 允许两种来源：
        //   1. 消费者直接报 failed（自定义消费者可以这么做）；
        //   2. 消费者只报 retried / deadLettered（本项目 Worker 就是这样），
        //      由后端求和推导。
        //
        // **为什么必须有第 2 条**：Worker 上报的是"进重试"与"进死信"两个
        // 互斥的结局，从不发 failed。若这里直接读 failed，那个指标会
        // **永远是 0** ——而它已经写进了运行手册与告警建议。
        // 一个恒为 0 的失败计数器比没有更糟：它让人以为"没有失败"。
        int retried = intOf(stats, "retried");
        int deadLettered = intOf(stats, "deadLettered");
        int failed = stats.containsKey("failed")
                ? intOf(stats, "failed")
                : retried + deadLettered;
        metrics.record(
                intOf(stats, "acked"),
                failed,
                retried,
                deadLettered,
                intOf(stats, "duplicates"),
                intOf(stats, "republished"),
                intOf(stats, "rejectFailed"));
        return Responses.ok(new ConsumerStatsResult(true));
    }

    /**
     * 读取计数，缺失或非法时按 0 处理。
     *
     * <p>负数按 0 处理：计数器倒退会让"处理了多少"失去意义，
     * 而一个明显不合理的负增量不如当作没上报。
     */
    private static int intOf(Map<String, Integer> stats, String key) {
        Integer value = stats.get(key);
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }
}
