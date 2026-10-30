package com.repopilot.modules.report.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.common.state.Verdict;
import com.repopilot.modules.agent.service.AgentVersionQueryService;
import com.repopilot.modules.annotation.service.ReviewedVerdictQueryService;
import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import com.repopilot.modules.evaluation.entity.EvaluationResult;
import com.repopilot.modules.evaluation.service.EvaluationReportQueryService;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.entity.GateRules;
import com.repopilot.modules.evaluationconfig.service.GateRuleReader;
import com.repopilot.modules.evaluationconfig.service.PlanVersionConfigQueryService;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.entity.ExperimentTarget;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.experiment.mapper.ExperimentTargetMapper;
import com.repopilot.modules.report.UsageReader;
import com.repopilot.modules.report.dto.ReportDtos;
import com.repopilot.modules.trace.dto.ReportRunRow;
import com.repopilot.modules.trace.entity.RunAttempt;
import com.repopilot.modules.trace.mapper.EvaluationRunMapper;
import com.repopilot.modules.trace.mapper.RunAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实验报告聚合（TASK-010）。
 *
 * <p>报告不是新的数据聚合，每次请求都从结构化表现算：
 * {@code experiments} / {@code evaluation_runs} / {@code evaluation_jobs} /
 * {@code evaluation_results} / 标注结果。V1 不建结果总表——那种表一旦与源数据漂移，
 * 报告就会开始说谎，而"说谎的报告"比"慢一点的报告"危险得多。压测证明有必要时再上物化视图，
 * 且缓存键必须含实验与标注视图版本。
 *
 * <p>全类通用的三条纪律：
 * <ol>
 *   <li>每个比率都带分母，分母为 0 时比率为 {@code null}；</li>
 *   <li>缺失与未知不进分子也不进分母，但必须单独计数展示；</li>
 *   <li>不同币种永不相加。</li>
 * </ol>
 */
@Service
public class ReportAggregationService {

    private final ExperimentMapper experimentMapper;
    private final ExperimentTargetMapper targetMapper;
    private final EvaluationRunMapper runMapper;
    private final RunAttemptMapper runAttemptMapper;
    private final EvaluationReportQueryService evaluationReports;
    private final PlanVersionConfigQueryService planConfig;
    private final GateRuleReader gateRuleReader;
    private final AgentVersionQueryService agentVersions;
    private final ReviewedVerdictQueryService reviewedVerdicts;

    public ReportAggregationService(ExperimentMapper experimentMapper,
                                    ExperimentTargetMapper targetMapper,
                                    EvaluationRunMapper runMapper,
                                    RunAttemptMapper runAttemptMapper,
                                    EvaluationReportQueryService evaluationReports,
                                    PlanVersionConfigQueryService planConfig,
                                    GateRuleReader gateRuleReader,
                                    AgentVersionQueryService agentVersions,
                                    ReviewedVerdictQueryService reviewedVerdicts) {
        this.experimentMapper = experimentMapper;
        this.targetMapper = targetMapper;
        this.runMapper = runMapper;
        this.runAttemptMapper = runAttemptMapper;
        this.evaluationReports = evaluationReports;
        this.planConfig = planConfig;
        this.gateRuleReader = gateRuleReader;
        this.agentVersions = agentVersions;
        this.reviewedVerdicts = reviewedVerdicts;
    }

    /** 标注视图：原始自动结论 / 已审核人工结论。 */
    public enum AnnotationView {
        /** 只看 evaluation_results 的自动结论。 */
        AUTOMATIC,
        /** 人工复核已通过时以人工结论为准，未复核的仍用自动结论。 */
        REVIEWED;

        public static AnnotationView parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTOMATIC;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "annotationView 只能是 AUTOMATIC 或 REVIEWED，收到: " + raw);
            }
        }
    }

    /** 一个槽位（Run）在某算子上的结论；技术失败与"没结论"分开标记。 */
    private record SlotVerdict(String evaluatorKey, Verdict verdict, BigDecimal score,
                               boolean technicalFailure) {

        /** 人工视图下人工结论覆盖自动结论；原始自动结论仍留在 evaluation_results 里。 */
        SlotVerdict withHuman(Verdict human) {
            return human == null ? this
                    : new SlotVerdict(evaluatorKey, human, score, technicalFailure);
        }
    }

    /** 单个 Run 在某批次上的全部算子结论。 */
    private record RunVerdicts(String runId, String targetKey, String datasetCaseId,
                               String caseKey, int repeatIndex,
                                Map<String, SlotVerdict> byEvaluator,
                                boolean anyTechnicalFailure) {

        Verdict dominant() {
            List<SlotVerdict> slots = new ArrayList<>(byEvaluator.values());
            if (slots.stream().anyMatch(s -> s.verdict() == Verdict.FAIL)) {
                return Verdict.FAIL;
            }
            if (slots.stream().anyMatch(s -> s.verdict() == Verdict.INDETERMINATE)) {
                return Verdict.INDETERMINATE;
            }
            if (slots.stream().anyMatch(s -> s.verdict() == Verdict.PASS)) {
                return Verdict.PASS;
            }
            return slots.isEmpty() ? null : slots.get(0).verdict();
        }

        /** 平均得分：任一算子无分则整体无分，绝不把 null 当 0 参与平均。 */
        BigDecimal score() {
            if (byEvaluator.isEmpty()) {
                return null;
            }
            List<BigDecimal> scores = byEvaluator.values().stream()
                    .map(SlotVerdict::score).filter(Objects::nonNull).toList();
            if (scores.size() != byEvaluator.size()) {
                return null;
            }
            return scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(scores.size()), 6, RoundingMode.HALF_UP);
        }
    }

    /**
     * 配对键：{@code (datasetCaseId, repeatIndex)}。同一 Case 的多次重复不是独立样本。
     *
     * <p>刻意用 {@code datasetCaseId} 而不是展示用的 {@code caseKey}：
     * caseKey 是业务可读标识，在不同数据集版本之间可能重复甚至复用；
     * 只有 ID 唯一，配对才不会把两个不同数据集的同名用例错配成一对。
     */
    private record PairKey(String datasetCaseId, int repeatIndex) {
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 生成实验报告。
     *
     * @param batchId             显式批次；为空则按 {@code batchScope} 解析
     * @param batchScope          {@code INITIAL}（默认）或 {@code LATEST}
     * @param baselineTargetKey   基线 Target key；为空时取第一个 Target
     * @param candidateTargetKey  候选 Target key；为空则不产出比较与门禁
     * @param annotationView      {@code AUTOMATIC}（默认）或 {@code REVIEWED}
     */
    @Transactional(readOnly = true)
    public ReportDtos.ExperimentReport report(String experimentId, String batchId,
                                             String batchScope, String baselineTargetKey,
                                             String candidateTargetKey, String annotationView) {
        Experiment experiment = requireExperiment(experimentId);
        EvaluationReportQueryService.BatchScope scope =
                EvaluationReportQueryService.BatchScope.parse(batchScope);
        EvaluationBatch batch = evaluationReports.resolveBatch(experimentId, batchId, scope);
        AnnotationView view = AnnotationView.parse(annotationView);

        List<ReportRunRow> allRuns = runMapper.listReportRows(experimentId);
        List<JobListRow> jobs = evaluationReports.jobsOfBatch(batch.getId());
        Map<String, EvaluationResult> results =
                evaluationReports.resultsByJobId(jobs.stream().map(job -> job.id).toList());
        Map<String, Verdict> humanBySlot = view == AnnotationView.REVIEWED
                ? humanVerdictsOf(experimentId) : Map.of();

        // 只有真正进了这个批次的 Run 才有结论。槽位覆盖率的分母却是 expectedRunCount，
        // 差额就是"应有但没评测"的槽位——这个差必须能被看见
        java.util.Set<String> batchRunIds = jobs.stream().map(job -> job.runId)
                .collect(Collectors.toSet());
        List<ReportRunRow> batchRuns = allRuns.stream()
                .filter(run -> batchRunIds.contains(run.id)).toList();
        // Judge 成本归属必须经由这张索引：JobAttempt 只认 jobId
        Map<String, String> runIdByJobId = jobs.stream().collect(Collectors.toMap(
                job -> job.id, job -> job.runId, (a, b) -> a));

        Map<String, List<RunVerdicts>> byTarget =
                buildRunVerdicts(batchRuns, jobs, results, humanBySlot);
        List<ExperimentTarget> targets = targetMapper.listByExperiment(experimentId);

        ReportDtos.PairComparison comparison =
                comparison(experiment, targets, baselineTargetKey, candidateTargetKey, byTarget);

        List<ReportDtos.TargetMetrics> metrics = metricsOf(experiment, targets, byTarget, allRuns,
                runAttemptsOf(experimentId), jobAttemptsOf(batch.getId()), runIdByJobId);

        EvaluationPlanVersion planVersion = planConfig.find(batch.getEvaluationPlanVersionId())
                .orElse(null);
        GateRules rules = gateRuleReader.read(
                planVersion == null ? null : planVersion.getConfigJson());
        ReportDtos.GateResult gate = GateEvaluator.evaluate(rules, comparison,
                candidateCostInCurrency(metrics, comparison, rules));

        return new ReportDtos.ExperimentReport(
                header(experiment, batch, planVersion, scope, view, rules),
                metrics, comparison, gate);
    }

    /**
     * 候选 Target 在**预算币种**上的最终观察成本合计。
     *
     * <p>用"最终观察"而不是"全部尝试"对预算：预算是给人看这次实验花了多少，
     * 平台内部的重试与 Judge 开销另有"全部尝试"口径。混用会让预算既不等于
     * 用户感知，也不等于平台真实成本。
     *
     * <p>只有恰好等于预算币种的那一条金额参与；没有这条金额就返回 {@code null}，
     * 由门禁判为"无法评估"，而不是拿别的币种凑一个数。
     */
    private static BigDecimal candidateCostInCurrency(
            List<ReportDtos.TargetMetrics> metrics,
            ReportDtos.PairComparison comparison, GateRules rules) {
        if (rules == null || rules.getCostBudget() == null || rules.getCostCurrency() == null
                || comparison == null) {
            return null;
        }
        String currency = rules.getCostCurrency();
        String targetKey = comparison.candidateTargetKey();
        return metrics.stream()
                .filter(metric -> targetKey.equals(metric.targetKey()))
                .findFirst()
                .map(metric -> metric.finalObservationCost().byCurrency().stream()
                        .filter(money -> currency.equals(money.currency()))
                        .map(ReportDtos.Money::amount)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null))
                .orElse(null);
    }

    private Experiment requireExperiment(String experimentId) {
        return experimentMapper.findById(experimentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + experimentId));
    }

    // ==================================================================
    // 头部上下文
    // ==================================================================

    private ReportDtos.ReportHeader header(Experiment experiment, EvaluationBatch batch,
                                           EvaluationPlanVersion planVersion,
                                           EvaluationReportQueryService.BatchScope scope,
                                           AnnotationView view, GateRules rules) {
        return new ReportDtos.ReportHeader(
                experiment.getId(),
                experiment.getName(),
                experiment.getStatus().name(),
                experiment.getExpectedRunCount(),
                batch.getId(),
                batch.getBatchNo() == null ? 0 : batch.getBatchNo(),
                scope.name(),
                batch.getStatus().name(),
                batch.getTriggerType().name(),
                batch.getEvaluationPlanVersionId(),
                planVersion == null ? null : planVersion.getVersion(),
                rules == null ? null : rules.getRuleVersion(),
                rules != null,
                view.name());
    }

    // ==================================================================
    // 结论装配
    // ==================================================================

    private Map<String, List<RunVerdicts>> buildRunVerdicts(List<ReportRunRow> runs,
                                                            List<JobListRow> jobs,
                                                            Map<String, EvaluationResult> results,
                                                            Map<String, Verdict> humanBySlot) {
        Map<String, List<JobListRow>> jobsByRun = jobs.stream()
                .collect(Collectors.groupingBy(job -> job.runId));

        Map<String, List<RunVerdicts>> byTarget = new LinkedHashMap<>();
        for (ReportRunRow run : runs) {
            Map<String, SlotVerdict> byEvaluator = new LinkedHashMap<>();
            boolean technicalFailure = false;
            // Run 级复核任务的结论覆盖该 Run 名下全部算子；Job 级只覆盖那一个 Job
            Verdict runLevelHuman = humanBySlot.get(run.id);
            for (JobListRow job : jobsByRun.getOrDefault(run.id, List.of())) {
                EvaluationResult result = results.get(job.id);
                Verdict verdict = result == null ? null : result.getVerdict();
                boolean jobFailed = evaluationReports.isTechnicalFailure(job, verdict);
                technicalFailure |= jobFailed;
                Verdict human = humanBySlot.getOrDefault(job.id, runLevelHuman);
                byEvaluator.put(job.evaluatorKey, new SlotVerdict(job.evaluatorKey,
                        verdict, result == null ? null : result.getScore(), jobFailed)
                        .withHuman(human));
            }
            RunVerdicts row = new RunVerdicts(run.id, run.targetKey, run.datasetCaseId,
                    run.caseKey, run.repeatIndex == null ? 0 : run.repeatIndex,
                    byEvaluator, technicalFailure);
            byTarget.computeIfAbsent(run.targetKey, k -> new ArrayList<>()).add(row);
        }
        return byTarget;
    }

    /** 已审核的人工结论：只取 REVIEW 且带 verdict 的记录，同槽位取最新一条。 */
    private Map<String, Verdict> humanVerdictsOf(String experimentId) {
        Map<String, Verdict> bySlot = new LinkedHashMap<>();
        //门面已按 (runId, 审核时间) 升序，同槽位后写覆盖先写即"取最新"
        reviewedVerdicts.reviewedVerdictsOfExperiment(experimentId)
                .forEach(row -> bySlot.put(row.slotKey(), parseVerdict(row.verdict())));
        return bySlot;
    }

    private static Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Verdict.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            // 库里出现字典外取值：当成"没结论"而不是猜一个。
            // 猜成 PASS/FAIL 会直接改变门禁结论，比报错难发现得多
            return null;
        }
    }

    private Map<String, List<RunAttempt>> runAttemptsOf(String experimentId) {
        Map<String, List<RunAttempt>> byRun = new LinkedHashMap<>();
        for (RunAttempt attempt : runAttemptMapper.listByExperiment(experimentId)) {
            byRun.computeIfAbsent(attempt.getRunId(), k -> new ArrayList<>()).add(attempt);
        }
        return byRun;
    }

    private Map<String, List<EvaluationJobAttempt>> jobAttemptsOf(String batchId) {
        return evaluationReports.attemptsOfBatch(batchId).stream()
                .collect(Collectors.groupingBy(EvaluationJobAttempt::getJobId));
    }

    // ==================================================================
    // 指标聚合
    // ==================================================================

    private List<ReportDtos.TargetMetrics> metricsOf(
            Experiment experiment, List<ExperimentTarget> targets,
            Map<String, List<RunVerdicts>> byTarget, List<ReportRunRow> allRuns,
            Map<String, List<RunAttempt>> runAttempts,
            Map<String, List<EvaluationJobAttempt>> jobAttempts,
            Map<String, String> runIdByJobId) {

        Map<String, List<ReportRunRow>> rowsByTarget = allRuns.stream()
                .collect(Collectors.groupingBy(run -> run.targetKey));
        long expectedSlots = expectedSlotsOf(experiment, targets.size());

        List<ReportDtos.TargetMetrics> metrics = new ArrayList<>();
        for (ExperimentTarget target : targets) {
            String key = target.getTargetKey();
            List<RunVerdicts> runs = byTarget.getOrDefault(key, List.of());
            List<ReportRunRow> rows = rowsByTarget.getOrDefault(key, List.of());

            // "已观察"= 至少拿到一个结论，而不是"这个槽位的 Job 被创建过"。
            // Job 一创建就进批次登记表，哪怕它还在 PENDING 或者已经技术失败——
            // 那种槽位没有可用的观察，算进覆盖率会让"只评了 1 个"显示成"覆盖 100%"
            long observedSlots = runs.stream()
                    .filter(run -> run.byEvaluator().values().stream()
                            .anyMatch(slot -> slot.verdict() != null))
                    .count();
            long nonTerminalSlots = countRows(rows, RunStatus.PENDING, RunStatus.QUEUED,
                    RunStatus.RUNNING, RunStatus.EVALUATING);
            // 缺失槽位 = 应有 − 已观察 − 还在跑。三者互斥，
            // 否则同一个槽位会既算"非终态"又算"缺失"，分母外的两个数字互相污染
            long missingSlots = Math.max(
                    expectedSlots - observedSlots - nonTerminalSlots, 0);
            ReportDtos.Ratio coverage = ReportDtos.Ratio.of(observedSlots, expectedSlots,
                    0, missingSlots + nonTerminalSlots);

            long completed = 0;
            long failed = 0;
            long timedOut = 0;
            long cancelled = 0;
            for (ReportRunRow row : rows) {
                switch (row.status) {
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case TIMED_OUT -> timedOut++;
                    case CANCELLED -> cancelled++;
                    default -> {
                        // 非终态已计入 nonTerminalSlots
                    }
                }
            }
            ReportDtos.Ratio technical = ReportDtos.Ratio.of(completed, expectedSlots,
                    failed + timedOut + cancelled, missingSlots + nonTerminalSlots);

            long success = 0;
            long failure = 0;
            long unknownOutcome = 0;
            long notApplicableOutcome = 0;
            long outcomeMissing = 0;
            for (ReportRunRow row : rows) {
                if (row.status != RunStatus.COMPLETED) {
                    outcomeMissing++;
                    continue;
                }
                TaskOutcome outcome = row.taskOutcome;
                if (outcome == TaskOutcome.SUCCESS) {
                    success++;
                } else if (outcome == TaskOutcome.FAILURE) {
                    failure++;
                } else if (outcome == TaskOutcome.NOT_APPLICABLE) {
                    notApplicableOutcome++;
                } else {
                    unknownOutcome++;
                }
            }
            ReportDtos.Ratio taskRate = ReportDtos.Ratio.of(success, success + failure,
                    unknownOutcome + notApplicableOutcome, outcomeMissing);

            Map<String, ReportDtos.VerdictDistribution> verdicts = verdictDistribution(runs);

            List<BigDecimal> scores = new ArrayList<>();
            long scoreMissing = 0;
            for (RunVerdicts run : runs) {
                for (SlotVerdict slot : run.byEvaluator().values()) {
                    if (slot.score() == null) {
                        scoreMissing++;
                    } else {
                        scores.add(slot.score());
                    }
                }
            }

            List<Long> durations = new ArrayList<>();
            long durationMissing = 0;
            for (ReportRunRow row : rows) {
                Long ms = durationMsOf(row);
                if (ms == null) {
                    durationMissing++;
                } else {
                    durations.add(ms);
                }
            }

            AgentVersionQueryService.Label label =
                    agentVersions.labelOf(target.getAgentVersionId());
            metrics.add(new ReportDtos.TargetMetrics(key,
                    target.getAgentVersionId(),
                    label.label(),
                    label.label() != null,
                    expectedSlots,
                    observedSlots,
                    coverage,
                    completed, failed, timedOut, cancelled, missingSlots,
                    technical,
                    success, failure, unknownOutcome, notApplicableOutcome,
                    taskRate,
                    verdicts,
                    ReportDtos.Average.of(scores, scoreMissing),
                    ReportDtos.Percentiles.of(durations, durationMissing),
                    finalObservationCost(rows),
                    allAttemptsCost(rows, runAttempts, jobAttempts, runIdByJobId)));
        }
        return metrics;
    }

    /**
     * 单个 Target 的槽位数。
     *
     * <p>用 {@code expectedRunCount / Target 数} 而不是 {@code repeatCount × 用例数}：
     * 前者是创建实验时写下的承诺，后者在离线只导入部分用例时会给出更小的分母，
     * 让覆盖率虚高——那正是"缺失不可忽略"要防的事。
     */
    private static long expectedSlotsOf(Experiment experiment, int targetCount) {
        return experiment.getExpectedRunCount() / Math.max(targetCount, 1);
    }

    private static Map<String, ReportDtos.VerdictDistribution> verdictDistribution(
            List<RunVerdicts> runs) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (RunVerdicts run : runs) {
            for (SlotVerdict slot : run.byEvaluator().values()) {
                long[] counts = buckets.computeIfAbsent(slot.evaluatorKey(), k -> new long[6]);
                if (slot.technicalFailure()) {
                    counts[4]++;
                } else if (slot.verdict() == null) {
                    counts[5]++;
                } else {
                    switch (slot.verdict()) {
                        case PASS -> counts[0]++;
                        case FAIL -> counts[1]++;
                        case INDETERMINATE -> counts[2]++;
                        case NOT_APPLICABLE -> counts[3]++;
                    }
                }
            }
        }
        Map<String, ReportDtos.VerdictDistribution> result = new LinkedHashMap<>();
        buckets.forEach((key, counts) -> result.put(key,
                ReportDtos.VerdictDistribution.of(counts[0], counts[1], counts[2],
                        counts[3], counts[4], counts[5])));
        return result;
    }

    /** 只用真实的 started/finished。没结束的、离线没有可靠时间的都不参与耗时统计。 */
    private static Long durationMsOf(ReportRunRow run) {
        Instant started = run.startedAt;
        Instant finished = run.finishedAt;
        if (started == null || finished == null || finished.isBefore(started)) {
            return null;
        }
        return Duration.between(started, finished).toMillis();
    }

    /** 最终观察成本：只算 Run 自己记录的那一次 usage（在线取 Run 的、离线取导入的）。 */
    private static ReportDtos.CostBreakdown finalObservationCost(List<ReportRunRow> rows) {
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        long unknown = 0;
        for (ReportRunRow row : rows) {
            UsageReader.Cost cost = UsageReader.costOf(row.usageJson);
            if (cost.known()) {
                byCurrency.merge(cost.currency(), cost.amount(), BigDecimal::add);
            } else {
                unknown++;
            }
        }
        return breakdown(byCurrency, unknown, rows.size());
    }

    /**
     * 全部尝试成本：Agent 侧含所有 RunAttempt（含被重试掉的），Judge 侧含所有 JobAttempt。
     *
     * <p>Judge 侧加 {@code JUDGE:} 前缀而不是直接并进 Agent 的币种桶：两者计价口径可以不同，
     * 混在一个"总额"里会让人以为平台执行成本就是 Agent 的花费。
     *
     * <p>{@code runIdByJobId} 是必需的：JobAttempt 只知道 jobId，不知道自己属于哪个 Run，
     * 不建立这张索引就只能把全批次 Judge 成本算到每个 Target 头上。
     */
    private static ReportDtos.CostBreakdown allAttemptsCost(
            List<ReportRunRow> rows,
            Map<String, List<RunAttempt>> runAttempts,
            Map<String, List<EvaluationJobAttempt>> jobAttempts,
            Map<String, String> runIdByJobId) {
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        long unknown = 0;
        long samples = 0;
        for (ReportRunRow row : rows) {
            for (RunAttempt attempt : runAttempts.getOrDefault(row.id, List.of())) {
                samples++;
                UsageReader.Cost cost = UsageReader.costOf(attempt.getUsageJson());
                if (cost.known()) {
                    byCurrency.merge(cost.currency(), cost.amount(), BigDecimal::add);
                } else {
                    unknown++;
                }
            }
            for (EvaluationJobAttempt judge : judgeAttemptsOfRun(row.id, jobAttempts, runIdByJobId)) {
                samples++;
                UsageReader.Cost cost = UsageReader.costOf(judge.getUsageJson());
                if (cost.known()) {
                    byCurrency.merge("JUDGE:" + cost.currency(), cost.amount(), BigDecimal::add);
                } else {
                    unknown++;
                }
            }
        }
        return breakdown(byCurrency, unknown, samples);
    }

    /** 某个 Run 名下所有 Job 的全部 Attempt（含重试）。 */
    private static List<EvaluationJobAttempt> judgeAttemptsOfRun(
            String runId, Map<String, List<EvaluationJobAttempt>> attemptsByJobId,
            Map<String, String> runIdByJobId) {
        List<EvaluationJobAttempt> result = new ArrayList<>();
        attemptsByJobId.forEach((jobId, attempts) -> {
            if (runId.equals(runIdByJobId.get(jobId))) {
                result.addAll(attempts);
            }
        });
        return result;
    }

    private static ReportDtos.CostBreakdown breakdown(Map<String, BigDecimal> byCurrency,
                                                     long unknown, long samples) {
        List<ReportDtos.Money> money = byCurrency.entrySet().stream()
                .map(entry -> new ReportDtos.Money(
                        entry.getValue().setScale(6, RoundingMode.HALF_UP),
                        entry.getKey(), samples - unknown, unknown))
                .sorted(Comparator.comparing(ReportDtos.Money::currency))
                .toList();
        return new ReportDtos.CostBreakdown(money, unknown, samples);
    }

    // ==================================================================
    // 配对比较
    // ==================================================================

    private ReportDtos.PairComparison comparison(Experiment experiment,
                                                List<ExperimentTarget> targets,
                                                String baselineTargetKey,
                                                String candidateTargetKey,
                                                Map<String, List<RunVerdicts>> byTarget) {
        if (candidateTargetKey == null || candidateTargetKey.isBlank()) {
            return null;
        }
        String baseline = baselineTargetKey == null || baselineTargetKey.isBlank()
                ? targets.isEmpty() ? null : targets.get(0).getTargetKey()
                : baselineTargetKey;
        if (baseline == null) {
            return null;
        }
        if (baseline.equals(candidateTargetKey)) {
            // 单Target 的实验没有可比较的对象，这不是"非法请求"而是"没有比较"，
            // 按契约应当不产出 comparison——由上层返回 null，而不是 400。
            // 只有在两侧确实是不同 Target 时才需要用户改参数
            boolean sameTargetExists = targets.stream()
                    .anyMatch(t -> !baseline.equals(t.getTargetKey()));
            if (sameTargetExists) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "基线与候选不能是同一个 Target: " + candidateTargetKey
                                + "（自我比较会得出\"全部稳定、零回归\"的假象）");
            }
            return null;
        }
        ExperimentTarget baselineTarget = targets.stream()
                .filter(t -> baseline.equals(t.getTargetKey())).findFirst().orElse(null);
        ExperimentTarget candidateTarget = targets.stream()
                .filter(t -> candidateTargetKey.equals(t.getTargetKey())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "实验 " + experiment.getId() + " 没有 Target: " + candidateTargetKey));

        Map<PairKey, RunVerdicts> baselineSlots = slotsOf(byTarget.get(baseline));
        Map<PairKey, RunVerdicts> candidateSlots = slotsOf(byTarget.get(candidateTargetKey));
        List<PairKey> allKeys = new ArrayList<>(baselineSlots.keySet());
        candidateSlots.keySet().stream().filter(k -> !baselineSlots.containsKey(k))
                .forEach(allKeys::add);
        allKeys.sort(Comparator.comparing(PairKey::datasetCaseId)
                .thenComparingInt(PairKey::repeatIndex));

        List<ReportDtos.PairedSlot> slots = new ArrayList<>();
        for (PairKey key : allKeys) {
            slots.add(pairOne(key, baselineSlots.get(key), candidateSlots.get(key)));
        }

        long regressions = count(slots, ReportDtos.Outcome.REGRESSION);
        long improvements = count(slots, ReportDtos.Outcome.IMPROVEMENT);
        long stable = count(slots, ReportDtos.Outcome.STABLE);
        long singleSided = count(slots, ReportDtos.Outcome.SINGLE_SIDED);
        long technicalError = count(slots, ReportDtos.Outcome.TECHNICAL_ERROR);
        long paired = regressions + improvements + stable;

        // 覆盖率分母是配对键空间总大小，不是成功配对数。
        // 否则单侧缺失会从分母里消失，"没数据"就被算成"没问题"
        ReportDtos.Ratio pairCoverage =
                ReportDtos.Ratio.of(paired, allKeys.size(), singleSided + technicalError, 0);

        AgentVersionQueryService.Label baselineLabel = agentVersions.labelOf(
                baselineTarget == null ? null : baselineTarget.getAgentVersionId());
        AgentVersionQueryService.Label candidateLabel =
                agentVersions.labelOf(candidateTarget.getAgentVersionId());
        boolean controlled = baselineLabel.label() != null && candidateLabel.label() != null;

        return new ReportDtos.PairComparison(baseline, candidateTargetKey,
                baselineLabel.label(), candidateLabel.label(),
                baselineLabel.label() != null, candidateLabel.label() != null,
                controlled,
                controlled ? null
                        : "至少一侧没有平台侧 Agent 版本，属于描述性对比，不构成受控因果实验",
                allKeys.size(), paired, singleSided, technicalError,
                regressions, improvements, stable,
                pairCoverage,
                slots,
                meanScoreOf(slots, true), meanScoreOf(slots, false),
                delta(meanScoreOf(slots, false), meanScoreOf(slots, true)));
    }

    private static long countRows(List<ReportRunRow> rows, RunStatus... statuses) {
        java.util.Set<RunStatus> wanted = java.util.Set.of(statuses);
        return rows.stream().filter(row -> wanted.contains(row.status)).count();
    }

    private static long count(List<ReportDtos.PairedSlot> slots, ReportDtos.Outcome outcome) {
        return slots.stream().filter(slot -> slot.outcome() == outcome).count();
    }

    private static Map<PairKey, RunVerdicts> slotsOf(List<RunVerdicts> runs) {
        Map<PairKey, RunVerdicts> map = new LinkedHashMap<>();
        if (runs == null) {
            return map;
        }
        for (RunVerdicts run : runs) {
            map.putIfAbsent(new PairKey(run.datasetCaseId(), run.repeatIndex()), run);
        }
        return map;
    }

    /**
     * 单个槽位的配对判定。
     *
     * <p>顺序有意为之：技术失败先判。技术失败时**没有质量结论可比**，
     * 若先比 PASS/FAIL 就会把"没跑出结果"当成"跑出结果且通过/失败"。
     */
    private static ReportDtos.PairedSlot pairOne(PairKey key, RunVerdicts left, RunVerdicts right) {
        // 展示用 caseKey（业务可读），配对用 datasetCaseId；单侧缺失时取存在那一侧
        String caseKey = (left != null ? left.caseKey() : right.caseKey());
        if (left == null || right == null) {
            RunVerdicts present = left == null ? right : left;
            return new ReportDtos.PairedSlot(caseKey, key.repeatIndex(),
                    left == null ? null : left.runId(),
                    right == null ? null : right.runId(),
                    left == null ? null : name(left.dominant()),
                    right == null ? null : name(right.dominant()),
                    left == null ? null : left.score(),
                    right == null ? null : right.score(),
                    null,
                    ReportDtos.Outcome.SINGLE_SIDED,
                    "仅 " + present.targetKey() + " 有观察，另一侧槽位缺失");
        }
        Verdict leftVerdict = left.dominant();
        Verdict rightVerdict = right.dominant();
        BigDecimal leftScore = left.score();
        BigDecimal rightScore = right.score();

        if (left.anyTechnicalFailure() || right.anyTechnicalFailure()) {
            return new ReportDtos.PairedSlot(caseKey, key.repeatIndex(),
                    left.runId(), right.runId(), name(leftVerdict), name(rightVerdict),
                    leftScore, rightScore, null,
                    ReportDtos.Outcome.TECHNICAL_ERROR,
                    "任一侧存在技术失败的 Job，无法判定质量");
        }
        ReportDtos.Outcome outcome;
        String note;
        if (leftVerdict == Verdict.PASS && rightVerdict == Verdict.FAIL) {
            outcome = ReportDtos.Outcome.REGRESSION;
            note = "基线 PASS，候选 FAIL";
        } else if (leftVerdict == Verdict.FAIL && rightVerdict == Verdict.PASS) {
            outcome = ReportDtos.Outcome.IMPROVEMENT;
            note = "基线 FAIL，候选 PASS";
        } else if (leftVerdict == null || rightVerdict == null) {
            outcome = ReportDtos.Outcome.SINGLE_SIDED;
            note = "任一侧没有可判定结论：不算通过，也不算回归";
        } else {
            outcome = ReportDtos.Outcome.STABLE;
            note = "双方结论一致: " + leftVerdict.name();
        }
        return new ReportDtos.PairedSlot(caseKey, key.repeatIndex(),
                left.runId(), right.runId(), name(leftVerdict), name(rightVerdict),
                leftScore, rightScore, delta(rightScore, leftScore), outcome, note);
    }

    private static String name(Verdict verdict) {
        return verdict == null ? null : verdict.name();
    }

    /** 有效配对的平均分：单侧缺失与技术错误不进均值（它们没有可比的分数）。 */
    private static BigDecimal meanScoreOf(List<ReportDtos.PairedSlot> slots, boolean baseline) {
        List<BigDecimal> values = slots.stream()
                .filter(slot -> slot.outcome() != ReportDtos.Outcome.SINGLE_SIDED
                        && slot.outcome() != ReportDtos.Outcome.TECHNICAL_ERROR)
                .map(slot -> baseline ? slot.baselineScore() : slot.candidateScore())
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal delta(BigDecimal to, BigDecimal from) {
        return to == null || from == null ? null
                : to.subtract(from).setScale(6, RoundingMode.HALF_UP);
    }
}