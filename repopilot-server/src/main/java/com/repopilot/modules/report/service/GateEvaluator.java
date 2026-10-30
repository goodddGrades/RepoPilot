package com.repopilot.modules.report.service;

import com.repopilot.common.state.Verdict;
import com.repopilot.modules.evaluationconfig.entity.GateRules;
import com.repopilot.modules.report.dto.ReportDtos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量门禁判定（TASK-010）。
 *
 * <p>判定顺序是契约，不可调换：
 * <ol>
 *   <li><b>先看有没有明确违规</b> → FAIL；</li>
 *   <li><b>无违规但证据不足</b> → INCONCLUSIVE；</li>
 *   <li><b>其余</b> → PASS。</li>
 * </ol>
 *
 * <p>为什么必须先判 FAIL：证据不足时"没观察到回归"不等于"没有回归"。
 * 若先判覆盖率不足就返回 INCONCLUSIVE，一个既有3 个回归、覆盖率又不够的报告会被
 * 降级成"证据不足"，那3 个回归就没人看了。反过来，只有在确认没有违规之后，
 * 才轮到讨论证据够不够。
 *
 * <p>门禁结果与 {@code Experiment.status} 无关：实验 COMPLETED 只说明平台跑完了，
 * 不说明质量达标；两者混用会让"技术完成"被误读成"质量通过"。
 */
final class GateEvaluator {

    private GateEvaluator() {
    }

    static ReportDtos.GateResult evaluate(GateRules rules,
                                          ReportDtos.PairComparison comparison,
                                          BigDecimal candidateCostInBudgetCurrency) {
        List<ReportDtos.GateFinding> violations = new ArrayList<>();
        List<ReportDtos.GateFinding> insufficient = new ArrayList<>();
        List<ReportDtos.GateFinding> passed = new ArrayList<>();

        // 没配置门禁规则 ≠ 通过：那是"没人定义过什么叫达标"，如实说出来
        if (rules == null) {
            insufficient.add(new ReportDtos.GateFinding("gateRules", "BLOCKING",
                    "该方案版本没有配置 gateRules，无法判定门禁", List.of()));
            return result("INCONCLUSIVE", null, "未配置门禁规则", comparison,
                    violations, insufficient, passed);
        }
        // 没有比较对象时同样不能判 PASS：那等于没看任何数据就说达标
        if (comparison == null) {
            insufficient.add(new ReportDtos.GateFinding("comparison", "BLOCKING",
                    "没有选择候选 Target，无法计算回归", List.of()));
            return result("INCONCLUSIVE", rules.getRuleVersion(), summarize(rules),
                    comparison, violations, insufficient, passed);
        }

        // ---- 第一步：明确违规
        if (rules.getMaxRegressions() != null
                && comparison.regressions() > rules.getMaxRegressions()) {
            violations.add(new ReportDtos.GateFinding("maxRegressions", "BLOCKING",
                    "回归数 " + comparison.regressions() + " 超过上限 "
                            + rules.getMaxRegressions(),
                    offendingRuns(comparison, ReportDtos.Outcome.REGRESSION)));
        }
        ReportDtos.GateFinding naFinding = notApplicableViolation(rules, comparison);
        if (naFinding != null) {
            violations.add(naFinding);
        }
        ReportDtos.GateFinding indeterminateFinding =
                indeterminateViolation(rules, comparison);
        if (indeterminateFinding != null) {
            violations.add(indeterminateFinding);
        }
        ReportDtos.GateFinding budgetFinding =
                costViolation(rules, candidateCostInBudgetCurrency);
        if (budgetFinding != null) {
            violations.add(budgetFinding);
        }

        if (!violations.isEmpty()) {
            return result("FAIL", rules.getRuleVersion(), summarize(rules), comparison,
                    violations, insufficient, passed);
        }

        // ---- 第二步：无违规，但证据不足
        BigDecimal required = rules.getMinValidPairCoverage();
        if (required != null) {
            BigDecimal actual = comparison.validPairCoverage().rate();
            if (actual == null || actual.compareTo(required) < 0) {
                insufficient.add(new ReportDtos.GateFinding("minValidPairCoverage", "BLOCKING",
                        "有效配对覆盖率 " + display(actual) + " 低于要求的 " + required.toPlainString(),
                        List.of()));
            } else {
                passed.add(new ReportDtos.GateFinding("minValidPairCoverage", "INFO",
                        "有效配对覆盖率 " + display(actual) + " 满足要求", List.of()));
            }
        }
        if (comparison.pairedSlots() == 0) {
            // 一个有效配对都没有时，任何阈值都是空集上的真值，必须说清楚
            insufficient.add(new ReportDtos.GateFinding("pairedSlots", "BLOCKING",
                    "没有任何有效配对，无法判定回归", List.of()));
        }
        if (comparison.singleSidedSlots() > 0) {
            insufficient.add(new ReportDtos.GateFinding("singleSidedSlots", "WARNING",
                    comparison.singleSidedSlots() + " 个槽位只有单侧观察，未纳入回归判定",
                    List.of()));
        }
        if (comparison.technicalErrorSlots() > 0) {
            insufficient.add(new ReportDtos.GateFinding("technicalErrorSlots", "WARNING",
                    comparison.technicalErrorSlots() + " 个槽位存在技术失败，未纳入回归判定",
                    List.of()));
        }

        if (!insufficient.stream().anyMatch(f -> "BLOCKING".equals(f.severity()))) {
            return result("PASS", rules.getRuleVersion(), summarize(rules), comparison,
                    violations, insufficient, passed);
        }
        return result("INCONCLUSIVE", rules.getRuleVersion(), summarize(rules), comparison,
                violations, insufficient, passed);
    }

    /**
     * NOT_APPLICABLE 的处理。
     *
     * <p>默认值是 {@code EXCLUDE}：不适用就是这次评测没有意义，既不该算通过也不该算失败。
     * 只有方案显式声明 {@code FAIL} 或 {@code COUNT_AS_NOT_PASS} 时它才进分母。
     */
    private static ReportDtos.GateFinding notApplicableViolation(GateRules rules,
                                                                  ReportDtos.PairComparison comparison) {
        GateRules.NaHandling handling = rules.naHandling();
        if (handling == GateRules.NaHandling.EXCLUDE) {
            return null;
        }
        long count = comparison.slots().stream()
                .filter(slot -> ReportDtos.Outcome.STABLE.equals(slot.outcome()))
                .filter(slot -> Verdict.NOT_APPLICABLE.name().equals(slot.candidateVerdict()))
                .count();
        if (count == 0) {
            return null;
        }
        return new ReportDtos.GateFinding("notApplicableHandling", "BLOCKING",
                handling == GateRules.NaHandling.FAIL
                        ? count + " 个槽位结论为 NOT_APPLICABLE，规则要求直接判违规"
                        : count + " 个槽位结论为 NOT_APPLICABLE，规则要求计入分母且不计通过",
                List.of());
    }

    /** INDETERMINATE 显式配置为视同未通过时的违规。 */
    private static ReportDtos.GateFinding indeterminateViolation(GateRules rules,
                                                                ReportDtos.PairComparison comparison) {
        if (!Boolean.TRUE.equals(rules.getIndeterminateCountsAsFailure())) {
            return null;
        }
        long count = comparison.slots().stream()
                .filter(slot -> ReportDtos.Outcome.STABLE.equals(slot.outcome()))
                .filter(slot -> Verdict.INDETERMINATE.name().equals(slot.candidateVerdict()))
                .count();
        if (count == 0) {
            return null;
        }
        return new ReportDtos.GateFinding("indeterminateCountsAsFailure", "BLOCKING",
                count + " 个槽位结论为 INDETERMINATE，规则要求视同未通过", List.of());
    }

    /**
     * 成本预算。
     *
     * <p>只与**同币种**比较：不同币种相加或比较没有依据（契约：不隐式兑换）。
     * 因此配置了预算就必须配币种（{@code GateRuleReader} 已强制），而实际成本里若只有
     * 别的币种，这里返回"无法评估"而不是当作超支或未超支。
     */
    private static ReportDtos.GateFinding costViolation(
            GateRules rules, BigDecimal candidateCostInBudgetCurrency) {
        BigDecimal budget = rules.getCostBudget();
        if (budget == null || rules.getCostCurrency() == null) {
            return null;
        }
        if (candidateCostInBudgetCurrency == null) {
            return new ReportDtos.GateFinding("costBudget", "BLOCKING",
                    "无法评估成本预算：没有 " + rules.getCostCurrency() + " 成本数据",
                    List.of());
        }
        if (candidateCostInBudgetCurrency.compareTo(budget) > 0) {
            return new ReportDtos.GateFinding("costBudget", "BLOCKING",
                    "成本 " + candidateCostInBudgetCurrency.toPlainString() + " "
                            + rules.getCostCurrency()
                            + " 超过预算 " + budget.toPlainString(),
                    List.of());
        }
        return null;
    }

    private static List<String> offendingRuns(ReportDtos.PairComparison comparison,
                                              ReportDtos.Outcome outcome) {
        return comparison.slots().stream()
                .filter(slot -> slot.outcome() == outcome)
                .map(ReportDtos.PairedSlot::candidateRunId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String summarize(GateRules rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("maxRegressions=").append(rules.getMaxRegressions());
        sb.append(", minValidPairCoverage=").append(rules.getMinValidPairCoverage());
        sb.append(", notApplicableHandling=").append(rules.naHandling());
        sb.append(", indeterminateCountsAsFailure=")
                .append(Boolean.TRUE.equals(rules.getIndeterminateCountsAsFailure()));
        if (rules.getCostBudget() != null) {
            sb.append(", costBudget=").append(rules.getCostBudget().toPlainString())
                    .append(" ").append(rules.getCostCurrency());
        }
        return sb.toString();
    }

    private static String display(BigDecimal value) {
        return value == null ? "无数据" : value.toPlainString();
    }

    private static ReportDtos.GateResult result(String verdict, Integer ruleVersion,
                                                String ruleSummary,
                                                ReportDtos.PairComparison comparison,
                                                List<ReportDtos.GateFinding> violations,
                                                List<ReportDtos.GateFinding> insufficient,
                                                List<ReportDtos.GateFinding> passed) {
        return new ReportDtos.GateResult(verdict, ruleVersion, ruleSummary,
                comparison == null ? 0 : comparison.pairedSlots(),
                comparison == null ? 0 : comparison.totalSlots(),
                comparison == null ? null : comparison.validPairCoverage().rate(),
                violations, insufficient, passed);
    }
}