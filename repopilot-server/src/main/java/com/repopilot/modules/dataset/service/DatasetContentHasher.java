package com.repopilot.modules.dataset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.modules.dataset.entity.DatasetCase;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 数据集版本内容摘要。
 *
 * <p><b>只有这一份实现</b>：逐条创建样本后发布、以及 JSONL 批量导入后发布，都必须经过这里。
 * 如果两条入口各写一份，就会出现"同样的样本、两个 hash"，I18 断言与历史对比全部失效。
 *
 * <p>payload 结构（与 {@code docs/architecture/03-database-design.md} 一致）：
 * <pre>
 * [ {caseKey, taskType, input, expected, metadata}, ... ]   按 caseKey 升序
 * </pre>
 * {@code expected} / {@code metadata} 缺失时**显式写 null**，不要省略键——
 * 省略键与写 null 在规范化后是不同的字节，会让"没填 expected"与"填了空对象"混为一谈。
 */
@Component
public class DatasetContentHasher {

    private static final Comparator<DatasetCase> BY_CASE_KEY =
            Comparator.comparing(DatasetCase::getCaseKey, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ObjectMapper objectMapper;

    public DatasetContentHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 计算一组样本的内容摘要；调用方需保证传入的是同一版本的全部样本。 */
    public String hashCases(List<DatasetCase> cases) {
        ArrayNode root = objectMapper.createArrayNode();
        cases.stream().sorted(BY_CASE_KEY).forEach(c -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("caseKey", c.getCaseKey());
            node.put("taskType", c.getTaskType());
            node.set("input", c.getInputJson() == null ? objectMapper.nullNode() : c.getInputJson());
            node.set("expected", c.getExpectedJson() == null ? objectMapper.nullNode() : c.getExpectedJson());
            node.set("metadata", c.getMetadataJson() == null ? objectMapper.nullNode() : c.getMetadataJson());
            root.add(node);
        });
        return Sha256.ofBytes(CanonicalJson.canonicalBytes(root));
    }

    /** 配置类摘要（AgentVersion.config / PlanVersion 的 config）。 */
    public String hashConfig(JsonNode config) {
        return Sha256.ofBytes(CanonicalJson.canonicalBytes(config == null ? objectMapper.nullNode() : config));
    }

    /**
     * 计划版本摘要：{@code {config, items:[{evaluatorDefinitionId, executionOrder, required, config}]}}，
     * items 按 {@code executionOrder} 升序。
     */
    public String hashPlan(JsonNode config, List<PlanItemView> items) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("config", config == null ? objectMapper.nullNode() : config);
        ArrayNode array = root.putArray("items");
        items.stream()
                .sorted(Comparator.comparingInt(PlanItemView::executionOrder))
                .forEach(item -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("evaluatorDefinitionId", item.evaluatorDefinitionId());
                    node.put("executionOrder", item.executionOrder());
                    node.put("required", item.required());
                    node.set("config", item.config() == null ? objectMapper.nullNode() : item.config());
                    array.add(node);
                });
        return Sha256.ofBytes(CanonicalJson.canonicalBytes(root));
    }

    /** 计划项的最小视图，避免把实体直接暴露给摘要算法。 */
    public record PlanItemView(String evaluatorDefinitionId, int executionOrder, boolean required, JsonNode config) {
    }
}
