package com.repopilot.modules.evaluationconfig.entity;

import com.repopilot.common.state.Verdict;

import java.math.BigDecimal;

/**
 * 发布方案里冻结的门禁规则（TASK-010）。
 *
 * <p>规则是**方案配置**，不是代码常量：同一个方案版本发出去之后，它的门禁含义
 * 就固定了，日后改阈值必须是新版本，否则历史批次的门禁结论无法复现。
 *
 * <p>{@code ruleVersion} 一起返回不是为了好看：规则字段含义变了（哪怕阈值没变），
 * 旧结论就不能和新结论并列比较，报告需要靠它把不同规则版本的结论隔开。
 *
 * <p>字段与 {@code docs/contracts/openapi-reporting.yaml} 的 {@code GateRules} 一一对应。
 */
public class GateRules {

    /** 规则版本号；同一实验内不同 ruleVersion 的门禁结论不可直接比较。 */
    private Integer ruleVersion;
    /** 参与门禁的算子 key；空表示用批次内全部 required 算子。 */
    private String evaluatorKey;
    /** 允许的最大回归数（PASS→FAIL 的配对数量）。超过即 FAIL。 */
    private Integer maxRegressions;
    /**
     * 最低有效配对覆盖率（小数，0~1）。低于此值不判 FAIL，而是 INCONCLUSIVE。
     *
     * <p>为什么覆盖率不足是"证据不足"而不是"通过"：样本太少时就算一个回归都没观察到，
     * 也不能说明没问题。把它当 PASS 等于让"数据缺失"变成"质量达标"。
     */
    private BigDecimal minValidPairCoverage;
    /** INDETERMINATE 参与分母时是否视同未通过；null/false 表示不视同（走覆盖率判断）。 */
    private Boolean indeterminateCountsAsFailure;
    /** NOT_APPLICABLE 的处理：{@link NaHandling}。 */
    private String notApplicableHandling;
    /** 可选成本预算（按币种分列，不同币种不相加）。 */
    private BigDecimal costBudget;
    private String costCurrency;

    public enum NaHandling {
        /** 不计入分母，只报数量（默认）。 */
        EXCLUDE,
        /** 计入分母，但不算 PASS。 */
        COUNT_AS_NOT_PASS,
        /** 计入分母且直接视为违规。 */
        FAIL
    }

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(Integer ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getEvaluatorKey() {
        return evaluatorKey;
    }

    public void setEvaluatorKey(String evaluatorKey) {
        this.evaluatorKey = evaluatorKey;
    }

    public Integer getMaxRegressions() {
        return maxRegressions;
    }

    public void setMaxRegressions(Integer maxRegressions) {
        this.maxRegressions = maxRegressions;
    }

    public BigDecimal getMinValidPairCoverage() {
        return minValidPairCoverage;
    }

    public void setMinValidPairCoverage(BigDecimal minValidPairCoverage) {
        this.minValidPairCoverage = minValidPairCoverage;
    }

    public Boolean getIndeterminateCountsAsFailure() {
        return indeterminateCountsAsFailure;
    }

    public void setIndeterminateCountsAsFailure(Boolean indeterminateCountsAsFailure) {
        this.indeterminateCountsAsFailure = indeterminateCountsAsFailure;
    }

    public String getNotApplicableHandling() {
        return notApplicableHandling;
    }

    public void setNotApplicableHandling(String notApplicableHandling) {
        this.notApplicableHandling = notApplicableHandling;
    }

    public NaHandling naHandling() {
        if (notApplicableHandling == null || notApplicableHandling.isBlank()) {
            return NaHandling.EXCLUDE;
        }
        try {
            return NaHandling.valueOf(notApplicableHandling.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 非法取值不能静默退回默认：退回 EXCLUDE 会让本该 FAIL 的门禁变成 PASS
            throw new IllegalArgumentException(
                    "gateRules.notApplicableHandling 只能是 EXCLUDE / COUNT_AS_NOT_PASS / FAIL，收到: "
                            + notApplicableHandling, e);
        }
    }

    public BigDecimal getCostBudget() {
        return costBudget;
    }

    public void setCostBudget(BigDecimal costBudget) {
        this.costBudget = costBudget;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public void setCostCurrency(String costCurrency) {
        this.costCurrency = costCurrency;
    }

    /** UNKNOWN / NOT_APPLICABLE / 缺失**都不算 PASS**：这是门禁的第一条不变量。 */
    public boolean countsAsPass(Verdict verdict) {
        return verdict == Verdict.PASS;
    }
}