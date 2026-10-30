package com.repopilot.modules.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.agent.dto.AgentDtos;
import com.repopilot.modules.agent.service.AgentVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单个 Agent 版本：读取、编辑草稿、发布、归档。 */
@RestController
@RequestMapping("/api/v1/agent-versions")
public class AgentVersionController {

    private final AgentVersionService service;

    public AgentVersionController(AgentVersionService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AgentDtos.VersionView>> get(@PathVariable String id) {
        return Responses.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AgentDtos.VersionView>> update(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        return Responses.of(service.update(id, body, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<AgentDtos.VersionView>> publish(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(service.publish(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<AgentDtos.VersionView>> archive(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Responses.of(service.archive(id, IdempotencyDigest.requireKey(idempotencyKey)));
    }
}
