package com.repopilot.modules.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 报告响应契约（TASK-010）。
 *
 * <p>三条贯穿全类型的不变量，缺任何一条报告就是在骗人：
 *
 * <ol>
 *   <li><b>每个比率都带分母</b>。{@link Ratio} 的 {@code numerator}/{@code denominator}
 *       一起出现，{@code denominator = 0} 时 {@code rate} 为 {@code null} 而不是 0——
 *       "没有样本"和"样本全败"是两件事。
 *   <li><b>缺失不是 0</b>。{@code score} 为空就是为空，不补 0，也不参与平均值。
 *   <li><b>不同币种不相加</b>。{@link Money} 按币种分列，只在同币种内求和。
 * </ol>
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * 一个比率：分子、分母、比率、以及"没被计入分母"的那些。
     *
     * @param numerator分子
     * @param denominator 分母；<b>0 时 {@code rate} 必须是 null</b>
     * @param rate {@code numerator / denominator}；分母为 0 或未配置时为 null
     * @param excluded 未计入分母但确实发生过的数量（UNKNOWN、N/A、技术失败…），必须展示
     * @param missing 应有但完全没数据的数量
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ratio(long numerator, long denominator, BigDecimal rate,
                         long excluded, long missing) {

        public static Ratio of(long numerator, long denominator) {
            return of(numerator, denominator, 0, 0);
        }

        public static Ratio of(long numerator, long denominator, long excluded, long missing) {
            BigDecimal rate = denominator <= 0 ? null
                    : BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP);
            return new Ratio(numerator, denominator, rate, excluded, missing);
        }
    }

    /**
     * 平均值：只统计有效样本，并如实报告有效样本数。
     *
     * @param average 有效样本均值；无有效样本时为 null（不是 0）
     * @param validSampleCount 参与计算的数量
     * @param missingCount 有槽位但分数为空的数量
     */
    public record Average(BigDecimal average, long validSampleCount, long missingCount) {

        public static Average of(List<BigDecimal> values, long missingCount) {
            if (values.isEmpty()) {
                return new Average(null, 0, missingCount);
            }
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Average(sum.divide(BigDecimal.valueOf(values.size()), 6,
                    java.math.RoundingMode.HALF_UP), values.size(), missingCount);
        }
    }

    /**
     * 分位数（p50/p95）与有效样本数。
     *
     * <p>有效样本数为 0 时两个分位数都是 {@code null}：没有样本就没有分位数，
     * 返回 0 会让"全部观察都缺耗时"看起来像"耗时都是 0 毫秒"。
     */
    public record Percentiles(Long p50, Long p95, long validSampleCount, long missingCount) {

        public static Percentiles of(List<Long> values, long missingCount) {
            if (values.isEmpty()) {
                return new Percentiles(null, null, 0, missingCount);
            }
            List<Long> sorted = values.stream().sorted().toList();
            return new Percentiles(percentile(sorted, 0.50), percentile(sorted, 0.95),
                    sorted.size(), missingCount);
        }

        /**
         * 最近秩法（nearest-rank）：取第 {@code ceil(p * n)} 个观测值。
         *
         * <p>不用插值：耗时是真实测量值，插值出来的 p95 不是一个任何 Run 真实花掉的时间，
         * 下钻到 Run 列表会对不上。
         */
        private static long percentile(List<Long> sorted, double p) {
            int rank = (int) Math.ceil(p * sorted.size());
            int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
            return sorted.get(index);
        }
    }

    /**
     * 一笔金额，**永远带币种**。
     *
     * @param amount 金额；未知时为 null（不是 0）
     * @param currency ISO 币种代码；与 {@code amount} 同生共死
     * @param knownCount 已知金额的样本数；{@code unknownCount} 未知成本覆盖率的分母
     */
    public record Money(BigDecimal amount, String currency, long knownCount, long unknownCount) {

        public static Money unknown(long unknownCount) {
            return new Money(null, null, 0, unknownCount);
        }

        public boolean known() {
            return amount != null && currency != null;
        }
    }

    /**
     * 一个维度的成本汇总：Agent 与 Judge 分开，最终观察与全部尝试分开。
     *
     * <p>{@code byCurrency} 是 Map 而不是单个金额——不同币种相加没有意义，
     * 隐式兑换更没有依据，契约明确"不把不可观测成本算作零；不同币种分别报告"。
     */
    public record CostBreakdown(List<Money> byCurrency, long unknownCostCount, long sampleCount) {
    }

    /** 某个算子 key 的结论分布；四类结论分开列，不合并成"通过率"。 */
    /**
     * 某个算子 key 上的结论分布。
     *
     * <p>{@code passRate} 是**真正的 record 组件**而不是计算方法：Jackson 只序列化
     * record 组件，写成 {@code passRate()} 方法的话契约里声明了这个字段、响应里却永远
     * 不会出现它——前端读到的会是一个静默的 undefined，而不是明确的分母。
     */
    public record VerdictDistribution(long pass, long fail, long indeterminate,
                                      long notApplicable, long technicalFailure, long missing,
                                      Ratio passRate) {

        /** 通过率分母只含 PASS 与 FAIL：INDETERMINATE 是证据不足，不是不通过。 */
        public static VerdictDistribution of(long pass, long fail, long indeterminate,
                                             long notApplicable, long technicalFailure,
                                             long missing) {
            return new VerdictDistribution(pass, fail, indeterminate, notApplicable,
                    technicalFailure, missing,
                    Ratio.of(pass, pass + fail, indeterminate + notApplicable,
                            technicalFailure + missing));
        }
    }

    /** 单个 Target 的槽位级指标。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TargetMetrics(String targetKey,
                                String agentVersionId,
                                String agentVersionLabel,
                                boolean agentVersionKnown,
                                long expectedSlotCount,
                                long observedSlotCount,
                                Ratio slotCoverage,
                                long completedCount,
                                long failedCount,
                                long timedOutCount,
                                long cancelledCount,
                                long missingCount,
                                Ratio technicalCompletion,
                                long taskSuccessCount,
                                long taskFailureCount,
                                long taskUnknownCount,
                                long taskNotApplicableCount,
                                Ratio taskSuccessRate,
                                Map<String, VerdictDistribution> verdicts,
                                Average score,
                                Percentiles durationMs,
                                CostBreakdown finalObservationCost,
                                CostBreakdown allAttemptsCost) {
    }

    /** 报告顶部的实验与批次上下文；前端必须原样展示，不允许自己推断。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReportHeader(String experimentId,
                               String experimentName,
                               String experimentStatus,
                               long expectedRunCount,
                               String batchId,
                               int batchNo,
                               String batchScope,
                               String batchStatus,
                               String triggerType,
                               String planVersionId,
                               Integer planVersion,
                               Integer gateRuleVersion,
                               boolean gateConfigured,
                               String annotationView) {
    }

    /** 配对回归的单个槽位结果。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PairedSlot(String caseKey, int repeatIndex,
                             String baselineRunId, String candidateRunId,
                             String baselineVerdict, String candidateVerdict,
                             BigDecimal baselineScore, BigDecimal candidateScore,
                             BigDecimal scoreDelta,
                             Outcome outcome,
                             String note) {
    }

    /** 配对结果分类。 */
    public enum Outcome {
        /** PASS→FAIL：真回归，门禁看的就是这个。 */
        REGRESSION,
        /** FAIL→PASS：改善。 */
        IMPROVEMENT,
        /** 双方结论一致（含都 FAIL、都 INDETERMINATE）。 */
        STABLE,
        /** 只有一方有观察：不算回归也不算改善，但要展示，缺失不能当"没问题"。 */
        SINGLE_SIDED,
        /** 任一方技术失败（Job 没跑完），无法判定质量。 */
        TECHNICAL_ERROR
    }

    /**
     * 一次配对比较的完整结果。
     *
     * <p>{@code totalSlots} 是配对键空间的大小，{@code pairedSlots} 是真正双方都有观察的
     * 数量，两者必须同时展示——只给"回归 2 个"而不说总共有多少槽位，读者无法判断严重性。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PairComparison(String baselineTargetKey, String candidateTargetKey,
                                 String baselineAgentVersion, String candidateAgentVersion,
                                 boolean baselineAgentVersionKnown,
                                 boolean candidateAgentVersionKnown,
                                 boolean controlledComparison,
                                 String incomparabilityReason,
                                 long totalSlots, long pairedSlots,
                                 long singleSidedSlots, long technicalErrorSlots,
                                 long regressions, long improvements, long stableSlots,
                                 Ratio validPairCoverage,
                                 List<PairedSlot> slots,
                                 BigDecimal meanBaselineScore, BigDecimal meanCandidateScore,
                                 BigDecimal meanScoreDelta) {
    }

    /** 门禁结论里的单条违规或证据不足原因。 */
    public record GateFinding(String rule, String severity, String message,
                              List<String> offendingRunIds) {
    }

    /**
     * 门禁结果。
     *
     * <p>不复用 {@code Experiment.status}：实验技术完成（COMPLETED）与质量门禁
     * （PASS/FAIL/INCONCLUSIVE）是两件事，前者只说明平台跑完了，不说明质量达标。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GateResult(String verdict, Integer ruleVersion,
                             String ruleSummary, long pairedSlots, long totalSlots,
                             BigDecimal validPairCoverage,
                             List<GateFinding> violations,
                             List<GateFinding> insufficientEvidence,
                             List<GateFinding> passed) {
    }

    /** 报告完整响应。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExperimentReport(ReportHeader header,
                                   List<TargetMetrics> targets,
                                   PairComparison comparison,
                                   GateResult gate) {
    }
}