package com.repopilot.modules.evaluationconfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.modules.evaluationconfig.entity.GateRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 从 {@code PlanVersion.configJson.gateRules} 解析门禁规则。
 *
 * <p>放在 {@code evaluationconfig} 模块而不是报告模块：这段 JSON 的schema 属于配置模块，
 * 解析规则与写入规则必须在一起，否则字段改名会出现"写得进去、读不出来"的静默失败。
 *
 * <p>解析失败的处理要格外小心：门禁规则解析不出来时**不能**当作"没有规则 → 通过"，
 * 那等于把配置错误变成质量达标。因此解析异常一律向上抛，由接口返回 400/500，
 * 而不是悄悄给一个全空的 {@link GateRules}。
 */
@Component
public class GateRuleReader {

    private static final Logger log = LoggerFactory.getLogger(GateRuleReader.class);

    private static final String GATE_RULES = "gateRules";

    /**
     * 读取门禁规则；{@code configJson} 为空或没有 {@code gateRules} 时返回 {@code null}。
     *
     * <p>{@code null} 的含义是"这个方案版本没有配置门禁"，与"配置了但阈值都是默认值"
     * 是两回事，报告要分开显示。
     */
    public GateRules read(JsonNode configJson) {
        if (configJson == null || configJson.isNull() || !configJson.isObject()) {
            return null;
        }
        JsonNode node = configJson.get(GATE_RULES);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "configJson.gateRules 必须是对象，收到: " + node.getNodeType());
        }
        GateRules rules = new GateRules();
        rules.setRuleVersion(intOrNull(node, "ruleVersion"));
        rules.setEvaluatorKey(textOrNull(node, "evaluatorKey"));
        rules.setMaxRegressions(intOrNull(node, "maxRegressions"));
        rules.setMinValidPairCoverage(decimalOrNull(node, "minValidPairCoverage"));
        JsonNode indeterminate = node.get("indeterminateCountsAsFailure");
        rules.setIndeterminateCountsAsFailure(
                indeterminate == null || indeterminate.isNull() ? null : indeterminate.asBoolean());
        rules.setNotApplicableHandling(textOrNull(node, "notApplicableHandling"));
        rules.setCostBudget(decimalOrNull(node, "costBudget"));
        rules.setCostCurrency(textOrNull(node, "costCurrency"));
        validate(rules);
        log.debug("读到门禁规则: ruleVersion={} evaluatorKey={} maxRegressions={}",
                rules.getRuleVersion(), rules.getEvaluatorKey(), rules.getMaxRegressions());
        return rules;
    }

    /** 规则自身的合法性：这里只挡"按这份规则判出来的结论一定没意义"的情况。 */
    private void validate(GateRules rules) {
        if (rules.getMaxRegressions() != null && rules.getMaxRegressions() < 0) {
            throw new IllegalArgumentException("gateRules.maxRegressions 不能为负");
        }
        BigDecimal coverage = rules.getMinValidPairCoverage();
        if (coverage != null
                && (coverage.compareTo(BigDecimal.ZERO) < 0 || coverage.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("gateRules.minValidPairCoverage 必须在 0 与 1 之间");
        }
        if (rules.getCostBudget() != null && rules.getCostBudget().signum() < 0) {
            throw new IllegalArgumentException("gateRules.costBudget 不能为负");
        }
        if (rules.getCostBudget() != null
                && (rules.getCostCurrency() == null || rules.getCostCurrency().isBlank())) {
            // 没有币种的预算无法与任何金额比较：不同币种不相加是硬规则，
            // 缺币种的预算只能被判为"无法评估"，不能拿去和某个币种的总成本比大小
            throw new IllegalArgumentException("配置 costBudget 时必须同时配置 costCurrency");
        }
        // 提前触发枚举解析，把非法取值暴露在读配置这一刻，而不是判门禁时
        rules.naHandling();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isTextual() && value.asText().isBlank()
                ? null : value.asText();
    }
}