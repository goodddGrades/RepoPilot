package com.repopilot.modules.evaluationconfig.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.web.Responses;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.service.EvaluatorDefinitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 算子定义查询（只读）。 */
@RestController
@RequestMapping("/api/v1/evaluators")
public class EvaluatorController {

    private final EvaluatorDefinitionService service;

    public EvaluatorController(EvaluatorDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EvaluationDtos.EvaluatorView>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        return Responses.ok(service.list(page, pageSize, key, type, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EvaluationDtos.EvaluatorView>> get(@PathVariable String id) {
        return Responses.ok(service.get(id));
    }
}
