package com.repopilot.common.health;

import com.repopilot.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础健康检查接口。
 *
 * <p>与 Spring Boot Actuator 的 {@code /actuator/health} 并存：
 * <ul>
 *   <li>{@code /api/health} 面向平台前端与业务客户端，返回统一响应结构与组件明细；</li>
 *   <li>{@code /actuator/health} 面向运维探针（K8s liveness/readiness、监控系统）。</li>
 * </ul>
 *
 * <p>无论依赖是否可用，本接口均返回 HTTP 200，由响应体 {@code data.status} 表达真实状态，
 * 便于前端在页面上展示“部分依赖异常”而不是直接报错。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public ApiResponse<HealthSnapshot> health() {
        return ApiResponse.ok(healthService.snapshot());
    }
}
