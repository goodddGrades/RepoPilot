package com.repopilot.common.reliability.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.reliability.service.RetentionService;
import com.repopilot.common.web.Responses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保留期清理的运维入口（{@code /internal/v1/messaging/retention}）。
 *
 * <p><b>为什么需要一个 HTTP 入口，而不是"看日志就行"</b>：
 * 清理任务是后台定时器，它成功时**不打印任何东西**（正常路径静默，
 * 否则每小时刷一行）。于是"清理一直在跑"和"清理早就停了"
 * 在日志里长得一模一样——都是空的。运维需要能直接问一句
 * "上次清理是什么时候、删了多少、成功还是失败"。
 *
 * <p>{@code GET} 返回该答案；{@code POST /purge} 触发一次与定时器
 * <b>完全相同</b>的清理（同一个 {@link RetentionService#purgeExpired()}），
 * 供演示与排障使用。两者都不改变清理规则本身——
 * 手动触发**不能**成为绕过"只删已发布且超期"这条铁律的入口。
 *
 * <p><b>没有提供"删这些 ID"或"按条件删"的接口</b>：那种接口一旦存在，
 * 就一定会有人用它删掉 PENDING 事件——而那是业务事实的唯一副本。
 * FAILED 的人工清理走 {@link RetentionService#purgeFailedOutbox}，
 * 强制要求显式时间下界，且只在代码里调用，不暴露为 HTTP。
 *
 * <p>鉴权复用 {@code WorkerAuthFilter}（覆盖整个 {@code /internal/v1} 前缀）。
 */
@RestController
@RequestMapping("/internal/v1/messaging/retention")
public class RetentionController {

    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    /** 清理状态：各状态行数、保留期配置、最后一次执行的删除量与结果。 */
    public record RetentionView(List<OutboxEventMapper.StatusCount> outboxByStatus,
                                int outboxRetentionDays,
                                int inboxRetentionDays,
                                int batchSize,
                                long lastOutboxDeleted,
                                long lastInboxDeleted,
                                String lastRunResult,
                                long lastRunEpochSecond,
                                long lastSuccessEpochSecond,
                                long lastFailureEpochSecond) {

        static RetentionView of(RetentionService service) {
            RetentionService.RetentionStatus status = service.status();
            return new RetentionView(
                    status.outboxByStatus(),
                    status.outboxRetentionDays(),
                    status.inboxRetentionDays(),
                    status.batchSize(),
                    status.lastOutboxDeleted(),
                    status.lastInboxDeleted(),
                    status.lastRunResult(),
                    service.lastRunEpochSecond(),
                    service.lastSuccessEpochSecond(),
                    service.lastFailureEpochSecond());
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<RetentionView>> status() {
        return Responses.ok(RetentionView.of(retention));
    }

    /**
     * 立即执行一轮清理，返回执行后的状态。
     *
     * <p>返回的是<b>执行后</b>的快照而不是"本次删了 N 行"：
     * 后者会诱使调用方把返回值当成累计值来用。删除量在
     * {@code lastOutboxDeleted / lastInboxDeleted} 里，语义明确是"最后一轮"。
     *
     * <p>幂等：没有可删的行时删除量为 0，重复调用不会报错也不会多删。
     */
    @PostMapping("/purge")
    public ResponseEntity<ApiResponse<RetentionView>> purge() {
        retention.purgeExpired();
        return Responses.ok(RetentionView.of(retention));
    }
}
