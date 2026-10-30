package com.repopilot.common.reliability.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.reliability.service.DeadLetterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信查询与受控重放的运维入口（TASK-013-C）。
 *
 * <p><b>为什么放在 {@code /internal/v1} 下</b>：这个前缀已被
 * {@code WorkerAuthFilter} 覆盖（Bearer 凭证 + 恒定时间比较）。
 * 死信重放是<b>能改变业务状态</b>的运维动作，绝不能开一个无鉴权的公开接口。
 * 复用已有的鉴权边界比新增一套凭据管理更简单也更安全。
 *
 * <p><b>刻意不提供"全量重放"</b>：见 {@link DeadLetterService} 的说明。
 * {@code eventIds} 为空时直接返回 400，而不是"那就把整个 DLQ 都重放一遍"。
 *
 * <p><b>类上的条件必须与 {@link DeadLetterService} 一致</b>：
 * 非 rabbit 发布模式（{@code repopilot.outbox.publisher=fault-injecting}）
 * 下 {@code DeadLetterService} 不注册，若本控制器仍无条件加载，
 * 构造器注入会失败并让**整个 Spring 上下文起不来**——
 * 表现为所有集成测试报 "Failed to load ApplicationContext"，
 * 而真正的根因（少一个 bean）被藏在栈的最深处，
 * 表面症状看起来与本控制器毫无关系。
 */
@RestController
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit",
        matchIfMissing = true)
@RequestMapping("/internal/v1/messaging/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetters;

    public DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    /** 重放请求。 */
    public record ReplayRequest(List<String> eventIds, Integer limit) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeadLetterService.DeadLetterSummary>>> query(
            @RequestParam(required = false) Integer limit) {
        int effective = limit == null ? DeadLetterService.DEFAULT_QUERY_LIMIT : limit;
        return ResponseEntity.ok(ApiResponse.ok(deadLetters.query(effective)));
    }

    @PostMapping("/replay")
    public ResponseEntity<ApiResponse<DeadLetterService.ReplayResult>> replay(
            @RequestBody ReplayRequest request) {
        if (request == null || request.eventIds() == null || request.eventIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "必须显式指定要重放的 eventId；本接口不提供全量重放");
        }
        if (request.eventIds().stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "eventId 不能为空");
        }
        int limit = request.limit() == null ? DeadLetterService.MAX_REPLAY_BATCH : request.limit();
        try {
            return ResponseEntity.ok(ApiResponse.ok(
                    deadLetters.replay(request.eventIds(), limit)));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ex.getMessage());
        }
    }
}
