package com.repopilot.modules.evaluationconfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;
import com.repopilot.modules.evaluationconfig.mapper.EvaluatorDefinitionMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 方案项解析与校验，创建与整体替换共用。
 *
 * <p>三条规则都来自数据库约束，如果让数据库来报错，用户只会看到 409「资源冲突」：
 * <ul>
 *   <li>同一版本内不允许重复算子（唯一键 {@code (plan_version_id, evaluator_definition_id)}）；</li>
 *   <li>{@code executionOrder} 不允许重复且必须 ≥1（唯一键 + CHECK）；</li>
 *   <li>算子必须真实存在（外键）。</li>
 * </ul>
 * 在这里先检查一遍，是为了给出 {@code items[2].executionOrder} 这种能直接定位到表单的错误。
 */
@Component
public class PlanItems {

    /** 上限用于挡住误传的大数组；正常方案是 3~8 个算子。 */
    private static final int MAX_ITEMS = 50;

    private final EvaluatorDefinitionMapper evaluatorMapper;
    private final ObjectMapper objectMapper;

    public PlanItems(EvaluatorDefinitionMapper evaluatorMapper, ObjectMapper objectMapper) {
        this.evaluatorMapper = evaluatorMapper;
        this.objectMapper = objectMapper;
    }

    /** 已校验的方案项。 */
    public record Spec(String evaluatorDefinitionId, int executionOrder, boolean required, JsonNode config) {
    }

    public List<Spec> parse(JsonNode itemsNode) {
        if (itemsNode == null || itemsNode.isNull() || !itemsNode.isArray()) {
            throw BusinessException.field("items", "items 必须是数组");
        }
        if (itemsNode.isEmpty()) {
            throw BusinessException.field("items", "方案版本至少需要一个算子");
        }
        if (itemsNode.size() > MAX_ITEMS) {
            throw BusinessException.field("items", "方案项不能超过 " + MAX_ITEMS + " 个");
        }

        List<Spec> specs = new ArrayList<>(itemsNode.size());
        Set<String> evaluatorIds = new LinkedHashSet<>();
        Set<Integer> orders = new LinkedHashSet<>();

        for (int i = 0; i < itemsNode.size(); i++) {
            String prefix = "items[" + i + "]";
            JsonNode node = itemsNode.get(i);
            if (node == null || !node.isObject()) {
                throw BusinessException.field(prefix, "方案项必须是 JSON 对象");
            }

            JsonNode idNode = node.get("evaluatorDefinitionId");
            if (idNode == null || !idNode.isTextual() || idNode.textValue().isBlank()) {
                throw BusinessException.field(prefix + ".evaluatorDefinitionId",
                        "evaluatorDefinitionId 不能为空");
            }
            String evaluatorId = idNode.textValue();

            int order = i + 1;
            JsonNode orderNode = node.get("executionOrder");
            if (orderNode != null && !orderNode.isNull()) {
                if (!orderNode.isIntegralNumber() || orderNode.asInt() < 1) {
                    throw BusinessException.field(prefix + ".executionOrder",
                            "executionOrder 必须是大于等于 1 的整数");
                }
                order = orderNode.asInt();
            }

            boolean required = true;
            JsonNode requiredNode = node.get("required");
            if (requiredNode != null && !requiredNode.isNull()) {
                if (!requiredNode.isBoolean()) {
                    throw BusinessException.field(prefix + ".required", "required 必须是布尔值");
                }
                required = requiredNode.asBoolean();
            }

            JsonNode config = node.get("config");
            if (config != null && !config.isNull() && !config.isObject()) {
                throw BusinessException.field(prefix + ".config", "config 必须是 JSON 对象");
            }
            JsonNode normalizedConfig = config == null || config.isNull()
                    ? objectMapper.createObjectNode() : config;

            if (!evaluatorIds.add(evaluatorId)) {
                throw BusinessException.field(prefix + ".evaluatorDefinitionId",
                        "同一方案版本内不允许出现重复算子");
            }
            if (!orders.add(order)) {
                throw BusinessException.field(prefix + ".executionOrder",
                        "executionOrder 不能重复: " + order);
            }

            specs.add(new Spec(evaluatorId, order, required, normalizedConfig));
        }

        Set<String> existing = evaluatorMapper.findByIds(evaluatorIds).stream()
                .map(EvaluatorDefinition::getId)
                .collect(Collectors.toSet());
        for (int i = 0; i < specs.size(); i++) {
            if (!existing.contains(specs.get(i).evaluatorDefinitionId())) {
                throw BusinessException.field("items[" + i + "].evaluatorDefinitionId",
                        "算子定义不存在: " + specs.get(i).evaluatorDefinitionId());
            }
        }
        return specs;
    }
}
