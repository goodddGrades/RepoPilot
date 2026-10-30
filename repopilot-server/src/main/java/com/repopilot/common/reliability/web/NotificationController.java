package com.repopilot.common.reliability.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.reliability.service.InboxService;
import com.repopilot.common.web.Responses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知接收入口（{@code POST /internal/v1/notifications/accept}）。
 *
 * <p>鉴权复用 {@code WorkerAuthFilter}（覆盖整个 {@code /internal/v1} 前缀）。
 *
 * <p><b>这个接口不创建任何 Run 或 Job，也不执行长任务</b>：
 * 它只做「短事务唤醒登记」——把消息记进 inbox 去重表，然后立刻返回。
 * Python Worker 收到 200 后唤醒本地领取循环，真正的任务仍走数据库领取协议。
 * 让这里顺手跑任务意味着要在一个 HTTP 请求里持有 MQ 消息未确认状态，
 * 一个慢任务就会占住 prefetch 配额，把整个消费者拖死。
 *
 * <p><b>返回 200 的两种情况</b>（契约要求）：
 * {@code ACCEPTED}（首次）与 {@code DUPLICATE}（重复）。
 * 二者都要 ACK——重复消息重投是正常现象，为此返回错误会让消费者无限重试。
 */
@RestController
@RequestMapping("/internal/v1/notifications")
public class NotificationController {

    private final InboxService inbox;

    public NotificationController(InboxService inbox) {
        this.inbox = inbox;
    }

    /** 请求正文：{@code {consumerName, event}}。 */
    public record AcceptRequest(String consumerName, JsonNode event) {
    }

    /** 响应：{@code {eventId, status, resourceId}}。 */
    public record AcceptResult(String eventId, String status, String resourceId) {
    }

    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<AcceptResult>> accept(@RequestBody AcceptRequest request) {
        if (request.consumerName() == null || request.consumerName().isBlank()) {
            throw BusinessException.field("consumerName", "consumerName 不能为空");
        }
        if (request.event() == null || request.event().isNull()) {
            throw BusinessException.field("event", "event 不能为空");
        }

        InboxService.Result result = inbox.accept(request.consumerName().trim(), request.event());

        return switch (result.outcome()) {
            // 首次与重复都返回 200：重复是正常现象，返回错误会让消费者无限重试
            case ACCEPTED, DUPLICATE -> Responses.ok(new AcceptResult(
                    result.eventId(), result.outcome().name(), resourceIdOf(request.event())));
            // 同 eventId 异内容：这是数据损坏或上游 bug，必须让调用方看见并停止重试。
            // 409 而不是 200，因为"当重复 ACK"会让它永远查不出来；
            // 也不是 400 —— 请求本身格式正确，是服务端已有的记录与它不一致。
            case PAYLOAD_CONFLICT -> throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "同 eventId 出现不同内容: " + result.detail());
            // 未知版本：400。消费者应进隔离队列，不应无限 requeue
            case UNSUPPORTED_VERSION -> throw new BusinessException(
                    ErrorCode.UNSUPPORTED_EVENT_VERSION,
                    "事件信封版本不受支持: " + result.detail());
            case MALFORMED -> throw BusinessException.field("event", result.detail());
        };
    }

    private static String resourceIdOf(JsonNode envelope) {
        return envelope.path("aggregateId").asText(null);
    }
}
