package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告聚合、配对回归与门禁（TASK-010）。
 *
 * <p>素材全部由**真实链路**跑出来：建实验 → Worker 领取 → 封口 → 生成评测 Job →
 * Worker 领取 Job → 提交真实 {@code evaluation_results}。没有用 JDBC 伪造结论行——
 * 那样测的是"报告能不能读我插进去的东西"，而不是"报告能不能读平台真正产出的东西"。
 *
 * <p>分母是本类的主要断言对象：每个比率都手工核对分子与分母，
 * 缺失槽位必须进覆盖率而不是消失。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-010 实验报告、配对回归与门禁")
class ReportAggregationIT extends AbstractExecutionIT {

    // ================================================================== ① 指标与分母

    @Test
    @DisplayName("技术完成率分母是应有槽位数，缺失槽位进 missing 而不是消失")
    void slotCoverageUsesExpectedCountAsDenominator() {
        Prepared prepared = prepared(1, 2, List.of("PASS", "FAIL"), null);
        JsonNode metrics = metricsOf(prepared.experimentId(), "target-1");

        assertThat(metrics.path("expectedSlotCount").asLong()).isEqualTo(2);
        assertThat(metrics.path("observedSlotCount").asLong()).isEqualTo(2);
        assertThat(metrics.path("slotCoverage").path("denominator").asLong()).isEqualTo(2);
        assertThat(metrics.path("slotCoverage").path("numerator").asLong()).isEqualTo(2);
        assertThat(metrics.path("slotCoverage").path("rate").asDouble()).isEqualTo(1.0);
        assertThat(metrics.path("slotCoverage").path("missing").asLong()).isZero();
        assertThat(metrics.path("technicalCompletion").path("denominator").asLong())
                .as("技术完成率的分母同样是应有槽位，不是已完成数").isEqualTo(2);
    }

    @Test
    @DisplayName("实验应有 2 槽位但只评 1 个时，未评槽位进入覆盖率的 missing")
    void missingSlotsShowUpInCoverage() {
        // 第2 个槽位的 Job 已领取但未提交：Run 存在、却没有结论。
        // 这与"根本没有 Run 行"是不同的两件事，都必须让覆盖率不足 1
        Prepared prepared = preparedPartially(1, 2, List.of("PASS"), null);
        JsonNode metrics = metricsOf(prepared.experimentId(), "target-1");

        assertThat(metrics.path("expectedSlotCount").asLong()).isEqualTo(2);
        assertThat(metrics.path("observedSlotCount").asLong())
                .as("只有拿到结论的槽位才算已观察").isEqualTo(1);
        assertThat(metrics.path("slotCoverage").path("numerator").asLong()).isEqualTo(1);
        assertThat(metrics.path("slotCoverage").path("denominator").asLong()).isEqualTo(2);
        assertThat(metrics.path("slotCoverage").path("rate").asDouble()).isEqualTo(0.5);
        assertThat(metrics.path("slotCoverage").path("missing").asLong())
                .as("未评的槽位必须出现在 missing 里，不能消失").isEqualTo(1);
        assertThat(metrics.path("technicalCompletion").path("denominator").asLong())
                .isEqualTo(2);
        assertThat(metrics.path("technicalCompletion").path("numerator").asLong())
                .as("技术完成只认 COMPLETED 的 Run").isEqualTo(1);
    }

    @Test
    @DisplayName("算子通过率分母只含 PASS 与 FAIL，INDETERMINATE 与 N-A 单列")
    void passRateExcludesIndeterminateAndNotApplicable() {
        Prepared prepared = prepared(3, 1, List.of("PASS", "FAIL", "INDETERMINATE"), null);
        JsonNode distribution = metricsOf(prepared.experimentId(), "target-1")
                .path("verdicts").path("output.non_empty");

        assertThat(distribution.path("pass").asLong()).isEqualTo(1);
        assertThat(distribution.path("fail").asLong()).isEqualTo(1);
        assertThat(distribution.path("indeterminate").asLong()).isEqualTo(1);
        assertThat(distribution.path("notApplicable").asLong()).isZero();
        //INDETERMINATE 是证据不足，不能算进通过率分母——否则它会被当成"没通过"
        assertThat(distribution.path("passRate").path("denominator").asLong()).isEqualTo(2);
        assertThat(distribution.path("passRate").path("numerator").asLong()).isEqualTo(1);
        assertThat(distribution.path("passRate").path("excluded").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_APPLICABLE 不计入通过率分母，也不算 PASS")
    void notApplicableIsNeitherPassNorFailure() {
        Prepared prepared = prepared(2, 1, List.of("NOT_APPLICABLE", "PASS"), null);
        JsonNode distribution = metricsOf(prepared.experimentId(), "target-1")
                .path("verdicts").path("output.non_empty");

        assertThat(distribution.path("notApplicable").asLong()).isEqualTo(1);
        assertThat(distribution.path("passRate").path("denominator").asLong())
                .as("N-A 不进分母").isEqualTo(1);
        assertThat(distribution.path("passRate").path("numerator").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("null 分数不进平均值，也不补 0")
    void nullScoreIsNeitherAveragedNorZeroFilled() {
        Prepared prepared = prepared(1, 2, List.of("PASS", "PASS"), null);
        // 手工把第二条结论的分数清空：分数可空是契约允许的，必须如实反映
        String secondJob = jdbc.queryForObject(
                "SELECT j.id FROM evaluation_jobs j JOIN evaluation_runs r ON r.id = j.run_id"
                        + " WHERE r.experiment_target_id = (SELECT id FROM experiment_targets"
                        + " WHERE experiment_id = ? AND target_key = 'target-1')"
                        + " ORDER BY r.repeat_index DESC LIMIT 1", String.class,
                prepared.experimentId());
        jdbc.update("UPDATE evaluation_results SET score = NULL WHERE job_id = ?", secondJob);

        JsonNode score = metricsOf(prepared.experimentId(), "target-1").path("score");

        assertThat(score.path("validSampleCount").asLong()).as("只有 1 个有效分数").isEqualTo(1);
        assertThat(score.path("missingCount").asLong()).isEqualTo(1);
        assertThat(score.path("average").decimalValue())
                .as("有效样本均值应等于那 1 个分数本身，而不是被 0 拉低").isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("全部无分时平均值为 null 而不是 0")
    void averageIsNullWhenNoValidSample() {
        Prepared prepared = prepared(1, 1, List.of("INDETERMINATE"), null);
        JsonNode score = metricsOf(prepared.experimentId(), "target-1").path("score");

        assertThat(score.path("validSampleCount").asLong()).isZero();
        assertThat(isNullOrMissing(score.get("average")))
                .as("没有有效样本就没有均值，返回 0 会被读成\"平均分 0 分\"").isTrue();
    }

    @Test
    @DisplayName("耗时缺 start/finish 时不进分位数，有效样本数如实为 0")
    void durationExcludesRunsWithoutTiming() {
        Prepared prepared = prepared(1, 1, List.of("PASS"), null);
        jdbc.update("UPDATE evaluation_runs SET started_at = NULL, finished_at = NULL"
                + " WHERE experiment_id = ?", prepared.experimentId());

        JsonNode duration = metricsOf(prepared.experimentId(), "target-1").path("durationMs");

        assertThat(duration.path("validSampleCount").asLong()).isZero();
        assertThat(duration.path("missingCount").asLong()).isEqualTo(1);
        assertThat(isNullOrMissing(duration.get("p50"))).isTrue();
        assertThat(isNullOrMissing(duration.get("p95"))).isTrue();
    }

    // ================================================================== ② 成本口径

    @Test
    @DisplayName("不同币种分别报告，不相加；未知成本不补 0")
    void costsAreGroupedByCurrencyAndUnknownIsNotZero() {
        Prepared prepared = prepared(1, 2, List.of("PASS", "PASS"), null);
        setRunUsage(prepared, 0, "{\"costAmount\":\"1.50\",\"currency\":\"USD\"}");
        setRunUsage(prepared, 1, "{\"costAmount\":\"20.00\",\"currency\":\"CNY\"}");

        JsonNode cost = metricsOf(prepared.experimentId(), "target-1")
                .path("finalObservationCost");
        List<String> currencies = new ArrayList<>();
        cost.path("byCurrency").forEach(money -> currencies.add(money.path("currency").asText()));

        assertThat(currencies).containsExactlyInAnyOrder("USD", "CNY");
        assertThat(cost.path("byCurrency").get(0).path("amount").decimalValue())
                .as("1.50 USD + 20.00 CNY 不能被加成一个数").isNotNull();
        assertThat(cost.path("unknownCostCount").asLong()).isZero();
    }

    @Test
    @DisplayName("没有成本数据时金额为 null 而不是 0")
    void unknownCostIsNullNotZero() {
        Prepared prepared = prepared(1, 1, List.of("PASS"), null);

        JsonNode cost = metricsOf(prepared.experimentId(), "target-1")
                .path("finalObservationCost");

        assertThat(cost.path("byCurrency").isEmpty())
                .as("没读到金额就不该列出币种条目").isTrue();
        assertThat(cost.path("unknownCostCount").asLong()).isEqualTo(1);
        assertThat(cost.path("sampleCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("最终观察成本与全部尝试成本分开，Judge 成本单列")
    void finalAndAllAttemptsCostsAreSeparated() {
        Prepared prepared = prepared(1, 1, List.of("PASS"), null);
        setRunUsage(prepared, 0, "{\"costAmount\":\"2.00\",\"currency\":\"USD\"}");

        JsonNode metrics = metricsOf(prepared.experimentId(), "target-1");
        JsonNode finalCost = metrics.path("finalObservationCost");
        JsonNode allAttempts = metrics.path("allAttemptsCost");

        assertThat(currencyAmount(finalCost, "USD"))
                .as("最终观察成本取 Run 上的 usage").isEqualByComparingTo("2.00");
        // 全部尝试口径来自 Attempt 上的 usage，而测试提交时没报成本
        assertThat(isNullOrMissing(currencyAmountNode(allAttempts, "USD")))
                .as("Attempt 没报成本就是未知，不能把Run 的值搬过来当成平台实际支出").isTrue();
        assertThat(allAttempts.path("unknownCostCount").asLong()).isPositive();
    }

    // ================================================================== ③ 配对回归

    @Test
    @DisplayName("同实验 Target 按 (case, repeatIndex) 配对，识别 PASS→FAIL 回归")
    void pairingDetectsPassToFailRegression() {
        // 基线 [PASS, PASS, PASS]；候选 [FAIL, PASS, PASS] → 1 个回归 + 2 个稳定
        Prepared prepared = paired("PASS", "FAIL", "PASS");

        JsonNode comparison = comparison(prepared.experimentId());

        assertThat(comparison.path("baselineTargetKey").asText()).isEqualTo("target-1");
        assertThat(comparison.path("candidateTargetKey").asText()).isEqualTo("target-2");
        assertThat(comparison.path("totalSlots").asLong()).as("3 个用例各重复 1 次").isEqualTo(3);
        assertThat(comparison.path("pairedSlots").asLong()).isEqualTo(3);
        assertThat(comparison.path("regressions").asLong()).isEqualTo(1);
        assertThat(comparison.path("improvements").asLong()).isZero();
        assertThat(comparison.path("stableSlots").asLong())
                .as("1 回归 + 2 稳定 = 3 个配对槽位").isEqualTo(2);

        JsonNode regressed = comparison.path("slots").get(0);
        assertThat(regressed.path("outcome").asText()).isEqualTo("REGRESSION");
        assertThat(regressed.path("baselineVerdict").asText()).isEqualTo("PASS");
        assertThat(regressed.path("candidateVerdict").asText()).isEqualTo("FAIL");
        assertThat(regressed.path("baselineRunId").asText()).isNotBlank();
        assertThat(regressed.path("candidateRunId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("FAIL→PASS 记为改善，相同结论记为稳定")
    void pairingClassifiesImprovementAndStable() {
        Prepared prepared = paired("FAIL", "PASS", "PASS");

        JsonNode comparison = comparison(prepared.experimentId());

        assertThat(comparison.path("improvements").asLong()).isEqualTo(1);
        assertThat(comparison.path("regressions").asLong()).isZero();
        assertThat(comparison.path("stableSlots").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("重复样本按 (case, repeatIndex) 各自配对，不合并成一次")
    void repeatsArePairedIndividuallyNotMerged() {
        // 1 用例 × 2 重复 × 2 Target = 4 个 Run；同一 case 的两次重复结论不同，
        // 合并成一个样本会把"同一 case 表现不稳定"这个事实抹掉
        Prepared prepared = pairedRepeats("PASS", "FAIL");
        JsonNode comparison = comparison(prepared.experimentId());

        assertThat(comparison.path("totalSlots").asLong())
                .as("配对键是 (case, repeatIndex)，2 次重复 = 2 个槽位").isEqualTo(2);
        assertThat(comparison.path("regressions").asLong()).isEqualTo(2);
        assertThat(comparison.path("meanBaselineScore").decimalValue())
                .as("两次重复各占一个样本，均值等于各自分数（都是 1.0）")
                .isEqualByComparingTo("1.0");
        assertThat(comparison.path("meanCandidateScore").decimalValue())
                .isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("单侧缺失单独计数，不进回归也不进成功配对")
    void singleSidedSlotsAreCountedSeparately() {
        Prepared prepared = pairedRepeats("PASS", "PASS");
        // 删掉候选 Target 的第二个 Run：模拟离线只导入部分用例
        String droppedRun = jdbc.queryForObject(
                "SELECT r.id FROM evaluation_runs r JOIN experiment_targets t"
                        + " ON t.id = r.experiment_target_id"
                        + " WHERE r.experiment_id = ? AND t.target_key = 'target-2'"
                        + " ORDER BY r.repeat_index DESC LIMIT 1", String.class,
                prepared.experimentId());
        deleteRunFully(droppedRun);

        JsonNode comparison = comparison(prepared.experimentId());

        assertThat(comparison.path("totalSlots").asLong())
                .as("配对键空间按两侧并集算，缺失不缩分母").isEqualTo(2);
        assertThat(comparison.path("singleSidedSlots").asLong()).isEqualTo(1);
        assertThat(comparison.path("pairedSlots").asLong()).isEqualTo(1);
        assertThat(comparison.path("validPairCoverage").path("denominator").asLong()).isEqualTo(2);
        assertThat(comparison.path("validPairCoverage").path("numerator").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("任一侧技术失败记为 TECHNICAL_ERROR，不当作质量回归")
    void technicalErrorsAreNotQualityRegressions() {
        Prepared prepared = pairedWithTechFailure();

        JsonNode comparison = comparison(prepared.experimentId());

        assertThat(comparison.path("technicalErrorSlots").asLong()).isEqualTo(1);
        assertThat(comparison.path("regressions").asLong())
                .as("技术失败不是质量回归，算进去会让人去查错方向").isZero();
        JsonNode errored = comparison.path("slots").get(0);
        assertThat(errored.path("outcome").asText()).isEqualTo("TECHNICAL_ERROR");
        assertThat(errored.path("note").asText()).contains("技术失败");
    }

    @Test
    @DisplayName("两侧都有平台 Agent 版本时才是受控对比，否则标为描述性")
    void unknownAgentVersionIsMarkedAsDescriptive() {
        Prepared prepared = paired("PASS", "FAIL", "PASS");

        JsonNode controlled = comparison(prepared.experimentId());

        assertThat(controlled.path("controlledComparison").asBoolean())
                .as("两侧都来自 seedPublished，Agent 版本已知").isTrue();
        assertThat(isNullOrMissing(controlled.get("incomparabilityReason"))).isTrue();
        assertThat(controlled.path("baselineAgentVersion").asText()).contains("v");
    }

    // ================================================================== ④ 门禁

    @Test
    @DisplayName("回归数超过上限判FAIL，并列出违规 Run")
    void gateFailsWhenRegressionBudgetExceeded() {
        // 基线全 PASS、候选前两个 FAIL → 2 个回归，超过上限 1
        Prepared prepared = pairedWithGate("""
                {"ruleVersion":1,"maxRegressions":1,"minValidPairCoverage":0.5}""",
                List.of("PASS", "PASS", "PASS"), List.of("FAIL", "FAIL", "PASS"));

        JsonNode gate = gate(prepared.experimentId());

        assertThat(gate.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(gate.path("ruleVersion").asInt()).isEqualTo(1);
        assertThat(gate.path("violations")).isNotEmpty();
        JsonNode violation = gate.path("violations").get(0);
        assertThat(violation.path("rule").asText()).isEqualTo("maxRegressions");
        assertThat(violation.path("offendingRunIds")).as("违规 Run 必须能下钻").isNotEmpty();
        assertThat(gate.path("pairedSlots").asLong()).isEqualTo(3);
        assertThat(gate.path("totalSlots").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("证据不足判 INCONCLUSIVE，即使一个回归都没有")
    void gateIsInconclusiveWhenCoverageTooLow() {
        // 要求 100% 覆盖，但第三个槽位技术失败，只有 2/3 能配对；
        // 规则很宽松（maxRegressions=5）所以没有明确违规 —— 只能判证据不足
        Prepared prepared = pairedWithGateAndTechFailure("""
                {"ruleVersion":1,"maxRegressions":5,"minValidPairCoverage":1.0}""",
                "PASS", "PASS");

        JsonNode gate = gate(prepared.experimentId());

        assertThat(gate.path("verdict").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(gate.path("violations")).as("证据不足不是违规").isEmpty();
        assertThat(gate.path("insufficientEvidence")).isNotEmpty();
        assertThat(gate.path("insufficientEvidence").get(0).path("rule").asText())
                .isEqualTo("minValidPairCoverage");
    }

    @Test
    @DisplayName("先判违规再判证据：既有回归又覆盖不足时必须报 FAIL")
    void explicitViolationOutranksInsufficientEvidence() {
        // 回归数超过上限 1，且覆盖率要求 100%（实际 2/3）——两个条件同时不满足。
        // 第三个槽位技术上失败：它既不配对也不算回归，但会把覆盖率压到 2/3
        Prepared prepared = pairedWithGateTwoRegressionsAndTechFailure("""
                {"ruleVersion":1,"maxRegressions":1,"minValidPairCoverage":1.0}""");

        JsonNode gate = gate(prepared.experimentId());

        assertThat(gate.path("verdict").asText())
                .as("若先判证据不足，这 2 个回归会被降级成\"没结论\"，等于没人看")
                .isEqualTo("FAIL");
        assertThat(gate.path("violations")).isNotEmpty();
    }

    @Test
    @DisplayName("证据充分且无违规时判 PASS")
    void gatePassesWhenEvidenceSufficientAndNoViolation() {
        Prepared prepared = pairedWithGate("""
                {"ruleVersion":2,"maxRegressions":3,"minValidPairCoverage":0.5}""",
                List.of("PASS", "PASS", "PASS"), List.of("PASS", "PASS", "PASS"));

        JsonNode gate = gate(prepared.experimentId());

        assertThat(gate.path("verdict").asText()).isEqualTo("PASS");
        assertThat(gate.path("ruleVersion").asInt()).isEqualTo(2);
        assertThat(gate.path("violations")).isEmpty();
        assertThat(gate.path("ruleSummary").asText()).contains("maxRegressions=3");
    }

    @Test
    @DisplayName("没配置 gateRules 时判 INCONCLUSIVE 而不是 PASS")
    void gateWithoutRulesIsInconclusiveNotPass() {
        Prepared prepared = paired("PASS", "PASS", "PASS");

        JsonNode gate = gate(prepared.experimentId());

        assertThat(gate.path("verdict").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(gate.path("insufficientEvidence").get(0).path("message").asText())
                .contains("没有配置 gateRules");
    }

    @Test
    @DisplayName("门禁结论不复用实验技术状态：技术完成不等于质量通过")
    void gateIsIndependentOfExperimentTechnicalStatus() {
        Prepared prepared = pairedWithGate("""
                {"ruleVersion":1,"maxRegressions":1,"minValidPairCoverage":0.5}""",
                List.of("PASS", "PASS", "PASS"), List.of("FAIL", "FAIL", "PASS"));

        String experimentStatus = jdbc.queryForObject(
                "SELECT status FROM experiments WHERE id = ?", String.class,
                prepared.experimentId());
        JsonNode gate = gate(prepared.experimentId());

        assertThat(experimentStatus).as("平台把该跑的跑完了").isEqualTo("COMPLETED");
        assertThat(gate.path("verdict").asText())
                .as("技术完成只说明平台交付了，不说明质量达标").isEqualTo("FAIL");
    }

    // ================================================================== ⑤ 批次与视图

    @Test
    @DisplayName("默认取初始批次，LATEST 取最新创建的批次，且回显批次上下文")
    void batchScopeSelectsDifferentBatches() {
        Prepared prepared = paired("PASS", "FAIL", "PASS");

        JsonNode initial = report(prepared.experimentId(), null);
        assertThat(initial.path("header").path("batchScope").asText()).isEqualTo("INITIAL");
        assertThat(initial.path("header").path("batchStatus").asText()).isNotBlank();
        assertThat(initial.path("header").path("batchNo").asInt()).isEqualTo(1);
        assertThat(initial.path("header").path("triggerType").asText()).isNotBlank();
        assertThat(initial.path("header").path("planVersionId").asText()).isNotBlank();
        assertThat(initial.path("header").path("annotationView").asText()).isEqualTo("AUTOMATIC");

        String latestBatchId = jdbc.queryForObject(
                "SELECT id FROM evaluation_batches WHERE experiment_id = ?"
                        + " ORDER BY batch_no DESC LIMIT 1", String.class,
                prepared.experimentId());
        JsonNode latest = report(prepared.experimentId(), "batchScope=LATEST");
        assertThat(latest.path("header").path("batchId").asText()).isEqualTo(latestBatchId);
        assertThat(latest.path("header").path("batchScope").asText()).isEqualTo("LATEST");
    }

    @Test
    @DisplayName("人工复核通过后，REVIEWED 视图用人工结论，AUTOMATIC 视图仍用自动结论")
    void annotationViewsDifferAfterReview() {
        Prepared prepared = paired("PASS", "FAIL", "PASS");
        // 对第一个回归槽位做人工复核，把 FAIL 改判为 PASS
        JsonNode regression = comparison(prepared.experimentId()).path("slots").get(0);
        String runId = regression.path("candidateRunId").asText();
        reviewVerdict(runId, "PASS");

        JsonNode automatic = report(prepared.experimentId(), null);
        JsonNode reviewed = report(prepared.experimentId(), "annotationView=REVIEWED");

        assertThat(automatic.path("comparison").path("regressions").asLong())
                .as("自动视图必须保留机器当初的判断").isEqualTo(1);
        assertThat(reviewed.path("header").path("annotationView").asText()).isEqualTo("REVIEWED");
        assertThat(reviewed.path("comparison").path("regressions").asLong())
                .as("人工把那个回归改判为 PASS 后，回归数归零").isZero();

        // 关键：人工结论绝不回写自动结论。
        // evaluation_results 只有 job_id（Run 归属要经 evaluation_jobs 绕一层），
        // 这里直接按 job_id 断言，也顺带确认自动结论仍是当初机器给的那条。
        String jobId = singleJobId(runId);
        assertThat(count("SELECT COUNT(*) FROM evaluation_results WHERE job_id = ?", jobId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verdict FROM evaluation_results WHERE job_id = ?",
                String.class, jobId))
                .as("evaluation_results 必须保持自动结论").isEqualTo("FAIL");
        // 人工结论另存：审核记录里能看到，且带被复核的自动结论
        assertThat(count("SELECT COUNT(*) FROM annotation_records ar"
                        + " JOIN annotation_tasks t ON t.id = ar.annotation_task_id"
                        + " WHERE t.run_id = ? AND ar.action = 'REVIEW' AND ar.verdict = 'PASS'",
                runId)).as("人工结论独立存放，不覆盖自动结论").isEqualTo(1);
    }

    @Test
    @DisplayName("报告是只读接口：不带幂等键也能重复调用且结果一致")
    void reportIsIdempotentReadOnly() {
        Prepared prepared = paired("PASS", "FAIL", "PASS");

        JsonNode first = report(prepared.experimentId(), null);
        JsonNode second = report(prepared.experimentId(), null);

        assertThat(second).as("只读接口重复调用结果必须一致").isEqualTo(first);
    }

    @Test
    @DisplayName("非法 batchScope 与 annotationView 返回 400 而不是静默降级")
    void invalidScopeAndViewAreRejected() {
        Prepared prepared = paired("PASS", "PASS", "PASS");

        ResponseEntity<String> badScope = get("/api/v1/experiments/" + prepared.experimentId()
                + "/report?batchScope=NEWEST");
        ResponseEntity<String> badView = get("/api/v1/experiments/" + prepared.experimentId()
                + "/report?annotationView=MANUAL");

        assertThat(badScope.getStatusCode().value()).isEqualTo(400);
        assertThat(badView.getStatusCode().value()).isEqualTo(400);
        assertThat(badScope.getBody()).contains("batchScope");
        assertThat(badView.getBody()).contains("annotationView");
    }

    @Test
    @DisplayName("未知实验与不属于实验的批次都返回 404")
    void unknownExperimentOrForeignBatchIsNotFound() {
        Prepared prepared = paired("PASS", "PASS", "PASS");
        String otherExperiment = prepared.experimentId();

        ResponseEntity<String> unknown = get("/api/v1/experiments/01JZZZZZZZZZZZZZZZZZZZZZZZ/report");
        ResponseEntity<String> foreignBatch = get("/api/v1/experiments/" + otherExperiment
                + "/report?batchId=01JZZZZZZZZZZZZZZZZZZZZZZZ");

        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(foreignBatch.getStatusCode().value()).isEqualTo(404);
    }

    // ==================================================================
    // 素材构造：全部走真实链路
    // ==================================================================

    private record Prepared(String experimentId, List<String> runIds) {
    }

    /**
     * 造一个 1 Target × N 用例 × repeatCount 重复的实验。
     *
     * @param verdicts **按 Run 逐个**给出的结论，长度必须等于 {@code caseCount × repeatCount}；
     *                 {@code null} 表示全部 PASS + 1.0 分
     * @param gateJson {@code gateRules} 对象 JSON；{@code null} 表示不配置门禁
     */
    private Prepared prepared(int caseCount, int repeatCount, List<String> verdicts,
                              String gateJson) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), gateJson);
        Scenario scenario = scenarioWith(planVersionId, caseCount);
        String experimentId = createExperiment(scenario, 1, repeatCount);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        assertThat(runIds).as("槽位数应为 用例数 × 重复数")
                .hasSize(caseCount * repeatCount);
        Map<String, List<String>> plan = new HashMap<>();
        plan.put("target-1", verdicts);
        submitPlan(experimentId, plan);
        return new Prepared(experimentId, runIds);
    }

    /**
     * 只评一部分槽位：其余的 Job 保持 PENDING，从未被领取。
     *
     * <p>这是"槽位缺失"的真实形态——离线只导入部分用例、或评测还在排队时看报告。
     * 刻意不用删Run 的方式造缺失：删掉Run 会让{@code expectedRunCount}
     * 与实际行数一起变，覆盖率就成了 100%，测不到"缺失进覆盖率"这条规则。
     */
    /**
     * 只评一部分槽位。
     *
     * <p>未评的槽位停在"Job 已领取但未提交"——这是真实系统里最常见的缺失形态
     * （评测还在跑、或Worker 挂了）。它与"根本没有 Run 行"是不同的两件事：
     * 前者Run 存在但没有结论，后者连槽位都没建。
     *
     * <p>刻意不删Run：删掉会让 {@code expectedRunCount} 与实际行数一起变，
     * 覆盖率就成了 100%，测不到"缺失进覆盖率"这条规则。
     */
    private Prepared preparedPartially(int caseCount, int repeatCount, List<String> verdicts,
                                       String gateJson) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), gateJson);
        Scenario scenario = scenarioWith(planVersionId, caseCount);
        String experimentId = createExperiment(scenario, 1, repeatCount);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        assertThat(runIds).hasSize(caseCount * repeatCount);

        Map<String, String> plan = new LinkedHashMap<>();
        List<String> targets = runsOfTarget(experimentId, "target-1");
        for (int i = 0; i < verdicts.size(); i++) {
            plan.put(targets.get(i), verdicts.get(i));
        }
        // 只领一轮：领到的按计划提交，不在计划里的持有租约不处理，
        // 让它们的 Run 停在 EVALUATING（技术层面"还没结论"）
        List<JsonNode> tasks = claimAs(workerId("eval"), "EVAL:output.non_empty");
        assertThat(tasks).as("一轮应能领到全部待评 Job").isNotEmpty();
        for (JsonNode task : tasks) {
            String runId = task.path("runId").asText();
            String verdict = plan.remove(runId);
            if (verdict != null) {
                completeClaimed(task, verdict, scoreOf(verdict), "报告测试注入",
                        "[]", "{\"empty\":false}", "RULE");
            }
        }
        assertThat(plan).as("计划内的 Run 都应拿到结论").isEmpty();
        return new Prepared(experimentId, runIds);
    }

    /** 两个 Target，各 3 个用例 × 1 重复；两侧结论按给定序列提交。 */
    private Prepared paired(String baselineVerdict, String candidateVerdict, String thirdVerdict) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), null);
        Scenario scenario = scenarioWith(planVersionId, 3);
        String experimentId = createExperiment(scenario, 2, 1);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        List<String> baseline = runsOfTarget(experimentId, "target-1");
        List<String> candidate = runsOfTarget(experimentId, "target-2");
        submitPlan(experimentId, Map.of(
                "target-1", repeatVerdict(baseline.size(), baselineVerdict, thirdVerdict),
                "target-2", repeatVerdict(candidate.size(), candidateVerdict, thirdVerdict)));
        return new Prepared(experimentId, runIds);
    }

    /**
     * 1 用例 × 2 重复 × 2 Target：每个 Target 2 个槽位，配对键是 2 个
     * {@code (case-1, 0)} 与 {@code (case-1, 1)}。
     */
    private Prepared pairedRepeats(String baselineVerdict, String candidateVerdict) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), null);
        Scenario scenario = scenarioWith(planVersionId, 1);
        String experimentId = createExperiment(scenario, 2, 2);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        assertThat(runIds).as("1 用例 × 2 重复 × 2 Target").hasSize(4);
        submitPlan(experimentId, Map.of(
                "target-1", List.of(baselineVerdict, baselineVerdict),
                "target-2", List.of(candidateVerdict, candidateVerdict)));
        return new Prepared(experimentId, runIds);
    }

    /**
     * 带 gateRules 的双 Target 实验，两侧各 3 个槽位。
     *
     * @param baselineVerdicts  基线逐槽位结论（长度 3）
     * @param candidateVerdicts 候选逐槽位结论（长度 3）
     */
    private Prepared pairedWithGate(String gateJson, List<String> baselineVerdicts,
                                    List<String> candidateVerdicts) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), gateJson);
        Scenario scenario = scenarioWith(planVersionId, 3);
        String experimentId = createExperiment(scenario, 2, 1);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        assertThat(runIds).as("3 用例 × 1 重复 × 2 Target").hasSize(6);
        submitPlan(experimentId, Map.of(
                "target-1", baselineVerdicts, "target-2", candidateVerdicts));
        return new Prepared(experimentId, runIds);
    }

    /**
     * 3 个槽位：前两个都是 PASS→FAIL 回归（超过上限 1），第三个候选侧技术失败。
     *
     * <p>这是最需要判定顺序正确的场景：既有明确违规（回归数超限），
     * 覆盖率又不足（2/3）。若先判证据不足，那2 个回归会被降级成"没结论"，
     * 等于没人看——所以必须报 FAIL。
     */
    private Prepared pairedWithGateTwoRegressionsAndTechFailure(String gateJson) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), gateJson);
        Scenario scenario = scenarioWith(planVersionId, 3);
        String experimentId = createExperiment(scenario, 2, 1);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        List<String> baselineRuns = runsOfTarget(experimentId, "target-1");
        List<String> candidateRuns = runsOfTarget(experimentId, "target-2");
        assertThat(candidateRuns).hasSize(3);

        Map<String, String> plan = new LinkedHashMap<>();
        plan.put(baselineRuns.get(0), "PASS");
        plan.put(candidateRuns.get(0), "FAIL");
        plan.put(baselineRuns.get(1), "PASS");
        plan.put(candidateRuns.get(1), "FAIL");
        plan.put(baselineRuns.get(2), "PASS");
        drainEvaluation(experimentId, plan, Set.of(candidateRuns.get(2)));
        return new Prepared(experimentId, runIds);
    }

    /**
     * 3 个槽位：前两个按给定结论评（可制造回归），第三个候选侧技术失败。
     *
     * <p>技术失败槽位既不参与配对也不算回归，但会把有效配对覆盖率压到 2/3——
     * 用来同时验证"明确违规优先于证据不足"这条判定顺序。
     */
    private Prepared pairedWithGateAndTechFailure(String gateJson, String baselineVerdict,
                                                  String candidateVerdict) {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), gateJson);
        Scenario scenario = scenarioWith(planVersionId, 3);
        String experimentId = createExperiment(scenario, 2, 1);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        List<String> candidateRuns = runsOfTarget(experimentId, "target-2");
        assertThat(candidateRuns).hasSize(3);

        // 前两个槽位正常评（1 个回归），第三个槽位的候选侧留给技术失败注入
        Map<String, String> plan = new LinkedHashMap<>();
        List<String> baselineRuns = runsOfTarget(experimentId, "target-1");
        for (int i = 0; i < 2; i++) {
            plan.put(baselineRuns.get(i), baselineVerdict);
            plan.put(candidateRuns.get(i), candidateVerdict);
        }
        plan.put(baselineRuns.get(2), baselineVerdict);
        drainEvaluation(experimentId, plan, Set.of(candidateRuns.get(2)));
        return new Prepared(experimentId, runIds);
    }

    /**
     * 2 个用例 × 2 Target：基线全 PASS；候选第1 个槽位技术失败，第 2 个正常评 PASS。
     *
     * <p>刻意造出"技术失败"与"正常完成"并存：只有两者同时在报告里出现，
     * 才能验证技术失败被单独计数而没有被当成质量回归。
     */
    private Prepared pairedWithTechFailure() {
        String planVersionId = publishPlan(List.of(
                item("output.non_empty", 1, "{\"outputPath\":\"answer\"}")), null);
        Scenario scenario = scenarioWith(planVersionId, 2);
        String experimentId = createExperiment(scenario, 2, 1);
        start(experimentId, idem("start"));
        List<String> runIds = finishRuns(experimentId);
        List<String> candidateRuns = runsOfTarget(experimentId, "target-2");
        assertThat(candidateRuns).as("2 用例 × 1 重复 × 1 Target").hasSize(2);

        // 候选第 1 个槽位留给技术失败注入：同一个 Job 会被反复领取、反复失败，直到重试耗尽
        Map<String, String> plan = new LinkedHashMap<>();
        runsOfTarget(experimentId, "target-1").forEach(runId -> plan.put(runId, "PASS"));
        plan.put(candidateRuns.get(1), "PASS");
        drainEvaluation(experimentId, plan, Set.of(candidateRuns.get(0)));

        assertThat(runStatus(candidateRuns.get(1)))
                .as("正常槽位已完成，与技术失败槽位区分开").isEqualTo("COMPLETED");
        return new Prepared(experimentId, runIds);
    }

    /** 领取并封口该实验下所有 Run（走真实 Worker 协议）。 */
    /**
     * 领取并封口该实验下所有 Run（走真实 Worker 协议）。
     *
     * <p>必须循环领取：{@code maxTasks} 有上限（5），而实验可能有 6 个以上 Run。
     * 只领一轮会让尾部 Run 永远停在 QUEUED——它在报告里表现为"缺失槽位"，
     * 但缺失的原因是测试自己没跑完，不是被测行为。
     */
    private List<String> finishRuns(String experimentId) {
        List<String> runIds = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<JsonNode> tasks = claimAs(workerId("runner"), "RUN:MOCK");
            if (tasks.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (JsonNode task : tasks) {
                String runId = task.path("runId").asText();
                String owner = jdbc.queryForObject(
                        "SELECT experiment_id FROM evaluation_runs WHERE id = ?",
                        String.class, runId);
                if (!experimentId.equals(owner)) {
                    continue;
                }
                List<String> eventIds = appendEvents(task.path("attemptId").asText(),
                        task.path("leaseToken").asText(), sampleEvents());
                assertThat(completeRun(task.path("attemptId").asText(),
                        task.path("leaseToken").asText(), "SUCCESS", "{\"answer\":\"42\"}",
                        eventIds).getStatusCode().value()).isEqualTo(200);
                runIds.add(runId);
                progressed = true;
            }
            if (!progressed) {
                // 本实验的 Run 已领完，剩下的属于别的用例
                break;
            }
        }
        return runIds;
    }

    /** 逐个领取评测 Job 并提交真实结论。 */
    /**
     * 按计划提交一批 Run 的结论。
     *
     * <p>刻意做成"先收集全部待提交 Run，再一次性领完并提交"：领取接口一次会返回多条任务，
     * 如果边领边提交（每个 Run 领一次），第一次领到的其他任务会因为没提交而**继续持有租约**，
     * 下一轮就领不到了——领不到不是断言失败，而是"这个 Run 没人评"，
     * 报告里会表现为静默的缺失。
     */
private void submitPlan(String experimentId, Map<String, List<String>> byTarget) {
        Map<String, String> plan = new LinkedHashMap<>();
        byTarget.forEach((targetKey, verdicts) -> {
            List<String> runIds = runsOfTarget(experimentId, targetKey);
            if (verdicts == null) {
                runIds.forEach(runId -> plan.put(runId, "PASS"));
                return;
            }
            assertThat(verdicts).as("Target %s 的结论数应与 Run 数一致", targetKey)
                    .hasSize(runIds.size());
            for (int i = 0; i < runIds.size(); i++) {
                plan.put(runIds.get(i), verdicts.get(i));
            }
        });
        drainEvaluation(experimentId, plan);
    }

    /** 第一个槽位用 first，其余用 rest。 */
    private static List<String> repeatVerdict(int count, String first, String rest) {
        List<String> verdicts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            verdicts.add(i == 0 ? first : rest);
        }
        return verdicts;
    }

    /**
     * 反复领取并提交，直到计划里的 Run 全部拿到结论。
     *
     * <p>循环而不是一次性：Job 之间按 {@code executionOrder} 串行，
     * 后面的 Job 在前面的完成前可能还领不到；而且领取有 {@code maxTasks} 上限，
     * 一次领不完时剩下的会一直持有租约，再领就领不到了。
     *
     * <p>遇到计划外的 Run 直接失败而不是给它编个结论：领到计划外的 Job 说明构造有
     * 意外副作用（跨用例残留或重复建实验），随手给它一个 PASS 会把问题掩盖成
     * "数字看起来正常"。
     */
    private void drainEvaluation(String experimentId, Map<String, String> plan) {
        drainEvaluation(experimentId, plan, Set.of());
    }

    /**
     * 提交计划内结论，同时把 {@code techFailureRunIds} 里的槽位反复失败到重试耗尽。
     *
     * <p>技术失败必须在**同一次领取循环里**注入，不能拆成"先排空其余 Job、再单独
     * 领取这个"：领取一次会返回多条任务，跳过而不处理的那条会一直持有租约，
     * 下一轮就再也领不到——现象是"任务不存在"，而真实原因是测试自己持着租约。
     * 合并后每条技术失败槽位都完整走完 领取→失败→退避→再领取→…→耗尽 的真实路径。
     *
     * @param techFailureRunIds 需要走到技术终态的 Run；它们不会产生任何质量结论
     */
    private void drainEvaluation(String experimentId, Map<String, String> plan,
                                 Set<String> techFailureRunIds) {
        Map<String, String> remaining = new LinkedHashMap<>(plan);
        Set<String> failing = new LinkedHashSet<>(techFailureRunIds);
        Set<String> exhausted = new LinkedHashSet<>();
        // 单次循环要容纳 正常提交 + 技术失败重试（默认 3 次），留足轮数
        for (int round = 0; round < 20; round++) {
            if (remaining.isEmpty() && exhausted.size() == techFailureRunIds.size()) {
                break;
            }
            List<JsonNode> tasks = claimAs(workerId("eval"), "EVAL:output.non_empty");
            if (tasks.isEmpty()) {
                break;
            }
            for (JsonNode task : tasks) {
                String runId = task.path("runId").asText();
                String verdict = remaining.remove(runId);
                if (verdict != null) {
                    completeClaimed(task, verdict, scoreOf(verdict), "报告测试注入",
                            "[]", "{\"empty\":false}", "RULE");
                    assertThat(runStatus(runId)).isEqualTo("COMPLETED");
                    continue;
                }
                if (!failing.contains(runId)) {
                    throw new AssertionError(
                            "领到了计划外的 Run " + runId + "，本用例的构造有副作用");
                }
                // 技术失败：注入可重试失败，并把退避窗口提前以便立刻进入下一次尝试
                String jobId = singleJobId(runId);
                jobFail(task.path("attemptId").asText(), task.path("leaseToken").asText(),
                        "TRACE_FETCH_FAILED", true);
                if ("FAILED".equals(jobStatus(jobId))) {
                    exhausted.add(runId);
                } else {
                    forceRetryDue(jobId);
                }
            }
        }
        assertThat(remaining).as("计划内的 Run 都应拿到结论").isEmpty();
        for (String runId : techFailureRunIds) {
            assertThat(runStatus(runId))
                    .as("技术失败槽位 %s 应走到终态", runId).isEqualTo("FAILED");
            assertThat(resultCount(singleJobId(runId)))
                    .as("技术失败不产生质量结论").isZero();
            assertThat(attemptStatuses(singleJobId(runId)))
                    .as("应真实重试到耗尽，而不是一步置为失败").hasSizeGreaterThanOrEqualTo(2);
        }
    }

    /** 只有 PASS/FAIL 有分数；INDETERMINATE 与 N-A 是无分数结论。 */
    private static BigDecimal scoreOf(String verdict) {
        return switch (verdict) {
            case "FAIL" -> new BigDecimal("0.0");
            case "PASS" -> new BigDecimal("1.0");
            default -> null;
        };
    }

    /** 走真实标注流程：建任务 → 领取 → 提交 → 审核通过。 */
    private void reviewVerdict(String runId, String verdict) {
        ResponseEntity<String> created = post("/api/v1/annotation-tasks", idem("rev"),
                "{\"runId\":\"" + runId + "\"}");
        assertThat(created.getStatusCode().value())
                .as("创建标注任务: %s", created.getBody()).isEqualTo(201);
        String taskId = dataId(created);

        ResponseEntity<String> claimed = post("/api/v1/annotation-tasks/" + taskId + "/claim",
                idem("claim"),
                "{\"expectedVersion\":" + data(created).path("version").asLong() + "}");
        assertThat(claimed.getStatusCode().value())
                .as("领取标注任务: %s", claimed.getBody()).isEqualTo(200);

        ResponseEntity<String> submitted = post("/api/v1/annotation-tasks/" + taskId + "/submit",
                idem("submit"),
                "{\"expectedVersion\":" + data(claimed).path("version").asLong()
                        + ",\"verdict\":\"" + verdict + "\",\"reason\":\"人工复核确认\"}");
        assertThat(submitted.getStatusCode().value())
                .as("提交标注: %s", submitted.getBody()).isEqualTo(200);
        assertThat(data(submitted).path("status").asText()).isEqualTo("SUBMITTED");

        ResponseEntity<String> reviewed = post("/api/v1/annotation-tasks/" + taskId + "/review",
                idem("review"),
                "{\"expectedVersion\":" + data(submitted).path("version").asLong()
                        + ",\"approved\":true,\"reason\":\"认可\"}");
        assertThat(reviewed.getStatusCode().value())
                .as("审核标注: %s", reviewed.getBody()).isEqualTo(200);
        assertThat(data(reviewed).path("status").asText()).isEqualTo("REVIEWED");
    }

    // ==================================================================
    // 查询与断言助手
    // ==================================================================

    private JsonNode report(String experimentId, String query) {
        String path = "/api/v1/experiments/" + experimentId + "/report"
                + "?candidateTargetKey=target-2" + (query == null ? "" : "&" + query);
        ResponseEntity<String> response = get(path);
        assertThat(response.getStatusCode().value())
                .as("报告查询应成功: %s", response.getBody()).isEqualTo(200);
        return data(response);
    }

    private JsonNode metricsOf(String experimentId, String targetKey) {
        String path = "/api/v1/experiments/" + experimentId + "/report?candidateTargetKey="
                + targetKey;
        JsonNode targets = data(get(path)).path("targets");
        for (JsonNode target : targets) {
            if (targetKey.equals(target.path("targetKey").asText())) {
                return target;
            }
        }
        throw new AssertionError("报告里没有 Target " + targetKey);
    }

    private JsonNode comparison(String experimentId) {
        JsonNode comparison = report(experimentId, null).path("comparison");
        assertThat(comparison.isMissingNode() || comparison.isNull())
                .as("未选候选 Target 时不该产出比较").isFalse();
        return comparison;
    }

    private JsonNode gate(String experimentId) {
        JsonNode gate = report(experimentId, null).path("gate");
        assertThat(gate.isMissingNode() || gate.isNull()).as("门禁结论必须存在").isFalse();
        return gate;
    }

    private List<String> runsOfTarget(String experimentId, String targetKey) {
        return jdbc.queryForList(
                "SELECT r.id FROM evaluation_runs r JOIN experiment_targets t"
                        + " ON t.id = r.experiment_target_id"
                        + " WHERE r.experiment_id = ? AND t.target_key = ?"
                        + " ORDER BY r.created_at ASC, r.id ASC",
                String.class, experimentId, targetKey);
    }

    private void setRunUsage(Prepared prepared, int index, String usageJson) {
        List<String> runIds = runsOfTarget(prepared.experimentId(), "target-1");
        jdbc.update("UPDATE evaluation_runs SET usage_json = ? WHERE id = ?",
                usageJson, runIds.get(index));
    }

    private BigDecimal currencyAmount(JsonNode breakdown, String currency) {
        JsonNode node = currencyAmountNode(breakdown, currency);
        return node == null || node.isMissingNode() ? null : node.decimalValue();
    }

    private JsonNode currencyAmountNode(JsonNode breakdown, String currency) {
        for (JsonNode money : breakdown.path("byCurrency")) {
            if (currency.equals(money.path("currency").asText())) {
                return money.path("amount");
            }
        }
        return null;
    }

    /**
     * 删掉一个已完成的 Run 及其全部下游产物。
     *
     * <p>删除顺序受外键约束：先断循环引用列，再自底向上删。
     * 刻意用真删而不是"标记缺失"，因为要验证的正是"报告能否处理槽位真的不存在"
     * ——离线导入只覆盖部分用例时就是这个形态。
     */
    private void deleteRunFully(String runId) {
        jdbc.update("DELETE FROM evaluation_results WHERE job_id IN"
                + " (SELECT id FROM evaluation_jobs WHERE run_id = ?)", runId);
        jdbc.update("DELETE FROM evaluation_job_attempts WHERE job_id IN"
                + " (SELECT id FROM evaluation_jobs WHERE run_id = ?)", runId);
        jdbc.update("UPDATE evaluation_jobs SET active_attempt_id = NULL WHERE run_id = ?",
                runId);
        jdbc.update("DELETE FROM evaluation_jobs WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM evaluation_batch_runs WHERE run_id = ?", runId);
        jdbc.update("UPDATE evaluation_runs SET active_attempt_id = NULL,"
                + " accepted_attempt_id = NULL WHERE id = ?", runId);
        jdbc.update("DELETE FROM trace_events WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM run_attempts WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM evaluation_runs WHERE id = ?", runId);
    }
}