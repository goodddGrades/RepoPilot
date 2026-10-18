package com.repopilot.modules.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.agent.dto.AgentDtos;
import com.repopilot.modules.agent.service.AgentService;
import com.repopilot.modules.agent.service.AgentVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 与配置版本接口。
 *
 * <p>Controller 只做三件事：取幂等键、调服务、包信封。没有任何业务判断——
 * 状态校验全部在服务层的事务与行锁内完成，否则并发下会被绕过。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentService agentService;
    private final AgentVersionService versionService;

    public AgentController(AgentService agentService, AgentVersionService versionService) {
        this.agentService = agentService;
        this.versionService = versionService;
    }

    @PostMapping("/agents")
    public ResponseEntity<ApiResponse<AgentDtos.AgentView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(agentService.create(body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<PageResponse<AgentDtos.AgentView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Responses.ok(agentService.list(page, pageSize, status, keyword));
    }

    @GetMapping("/agents/{id}")
    public ResponseEntity<ApiResponse<AgentDtos.AgentView>> get(@PathVariable String id) {
        return Responses.ok(agentService.get(id));
    }

    @PatchMapping("/agents/{id}")
    public ResponseEntity<ApiResponse<AgentDtos.AgentView>> patch(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(agentService.patch(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/agents/{id}/versions")
    public ResponseEntity<ApiResponse<AgentDtos.VersionView>> createVersion(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(versionService.create(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @GetMapping("/agents/{id}/versions")
    public ResponseEntity<ApiResponse<PageResponse<AgentDtos.VersionView>>> listVersions(
            @PathVariable String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Responses.ok(versionService.list(id, page, pageSize));
    }
}
