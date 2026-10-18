package com.repopilot.it;

import com.repopilot.common.id.Ulid;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.BatchTriggerType;
import com.repopilot.modules.agent.entity.Agent;
import com.repopilot.modules.agent.mapper.AgentMapper;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationBatchRun;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchRunMapper;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.mapper.EvaluationRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 持久化映射验收：JSON、UTC、ULID、枚举、复合主键、生成列。
 *
 * <p>只测"写进去能原样读回来"是不够的：时间还要验证落库值确实是 UTC，
 * 枚举要验证取到的是枚举而不是字符串，生成列要验证它不接受应用写入。
 */
@Transactional
class PersistenceMappingIT extends AbstractDatabaseIT {

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private DatasetCaseMapper datasetCaseMapper;

    @Autowired
    private EvaluationRunMapper evaluationRunMapper;

    @Autowired
    private EvaluationBatchMapper evaluationBatchMapper;

    @Autowired
    private EvaluationBatchRunMapper evaluationBatchRunMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ulidPrimaryKeyShouldRoundTrip() {
        String id = Ulid.newId();
        agentMapper.insert(agent(id, "CODE-" + id.substring(18)));

        Optional<Agent> found = agentMapper.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void malformedUlidShouldBeRejectedBeforeHittingDatabase() {
        Agent agent = agent("not-a-ulid", "CODE-X");

        assertThatThrownBy(() -> agentMapper.insert(agent))
                .hasStackTraceContaining("非法的 ULID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agents", Integer.class)).isZero();
    }

    @Test
    void nestedJsonShouldRoundTripWithoutLoss() {
        Fixtures fixtures = new Fixtures(jdbc);
        String datasetId = fixtures.dataset("json-" + Ulid.newId().substring(20));
        String versionId = fixtures.datasetVersion(datasetId, 1, "DRAFT");

        DatasetCase entity = new DatasetCase();
        entity.setId(Ulid.newId());
        entity.setDatasetVersionId(versionId);
        entity.setCaseKey("case-json");
        entity.setTaskType("CODING");
        entity.setInputJson(json("""
                {"prompt":"修复空指针","context":{"files":["a.java","b.java"],"tags":null},"retries":[1,2,3]}
                """));
        entity.setExpectedJson(json("{\"patch\":\"diff\"}"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        datasetCaseMapper.insert(entity);

        DatasetCase loaded = datasetCaseMapper.findById(entity.getId()).orElseThrow();
        assertThat(loaded.getInputJson()).isEqualTo(entity.getInputJson());
        assertThat(loaded.getInputJson().get("context").get("files")).hasSize(2);
        assertThat(loaded.getInputJson().get("context").get("tags").isNull()).isTrue();
        assertThat(loaded.getInputJson().get("prompt").asText()).isEqualTo("修复空指针");
        assertThat(loaded.getExpectedJson()).isEqualTo(entity.getExpectedJson());
    }

    @Test
    void instantShouldBeStoredAsUtcEvenWhenJvmTimeZoneIsNotUtc() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Fixtures fixtures = new Fixtures(jdbc);
            Fixtures.Graph graph = fixtures.seed();
            jdbc.update("DELETE FROM evaluation_batch_runs WHERE run_id = ?", graph.runId());
            jdbc.update("DELETE FROM evaluation_batches WHERE id = ?", graph.batchId());

            Instant moment = Instant.parse("2026-01-02T03:04:05.678Z");
            EvaluationBatch batch = batch(graph.experimentId(), graph.planVersionId(), 1,
                    BatchTriggerType.INITIAL);
            batch.setCreatedAt(moment);
            batch.setUpdatedAt(moment);
            evaluationBatchMapper.insert(batch);

            String stored = jdbc.queryForObject(
                    "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM evaluation_batches"
                            + " WHERE id = ?", String.class, batch.getId());
            assertThat(stored).startsWith("2026-01-02 03:04:05.678");

            EvaluationBatch loaded = evaluationBatchMapper.findById(batch.getId()).orElseThrow();
            assertThat(loaded.getCreatedAt()).isEqualTo(moment);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void statusColumnsShouldMapToEnums() {
        Fixtures fixtures = new Fixtures(jdbc);
        Fixtures.Graph graph = fixtures.seed();

        EvaluationBatch batch = evaluationBatchMapper.findById(graph.batchId()).orElseThrow();

        assertThat(batch.getTriggerType()).isEqualTo(BatchTriggerType.INITIAL);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.PENDING);
        assertThat(batch.getExpectedJobCount()).isZero();
        assertThat(batch.getSelectionJson().get("runIds").isArray()).isTrue();
    }

    @Test
    void generatedInitialColumnShouldBeDerivedByDatabase() {
        Fixtures fixtures = new Fixtures(jdbc);
        Fixtures.Graph graph = fixtures.seed();

        EvaluationBatch initial = evaluationBatchMapper.findById(graph.batchId()).orElseThrow();
        assertThat(initial.getInitialExperimentId()).isEqualTo(graph.experimentId());

        EvaluationBatch reevaluation = batch(graph.experimentId(), graph.planVersionId(), 2,
                BatchTriggerType.REEVALUATION);
        reevaluation.setParentBatchId(graph.batchId());
        evaluationBatchMapper.insert(reevaluation);

        EvaluationBatch loaded = evaluationBatchMapper.findById(reevaluation.getId()).orElseThrow();
        assertThat(loaded.getInitialExperimentId()).isNull();
        assertThat(loaded.getParentBatchId()).isEqualTo(graph.batchId());
    }

    @Test
    void compositePrimaryKeyShouldBeReadableByBothColumns() {
        Fixtures fixtures = new Fixtures(jdbc);
        Fixtures.Graph graph = fixtures.seed();

        Optional<EvaluationBatchRun> found =
                evaluationBatchRunMapper.findById(graph.batchId(), graph.runId());

        assertThat(found).isPresent();
        assertThat(found.get().getExperimentId()).isEqualTo(graph.experimentId());
    }

    @Test
    void runColumnsShouldMapBackToEnums() {
        Fixtures fixtures = new Fixtures(jdbc);
        Fixtures.Graph graph = fixtures.seed();

        EvaluationRun run = evaluationRunMapper.findById(graph.runId()).orElseThrow();

        assertThat(run.getMode().name()).isEqualTo("ONLINE");
        assertThat(run.getTaskOutcome().name()).isEqualTo("UNKNOWN");
        assertThat(run.getObservationMetadataJson().isObject()).isTrue();
        assertThat(run.getDatasetCaseId()).isEqualTo(graph.caseId());
    }

    private Agent agent(String id, String code) {
        Instant now = Instant.now();
        Agent agent = new Agent();
        agent.setId(id);
        agent.setCode(code);
        agent.setName("测试 Agent");
        agent.setAgentType("MOCK");
        agent.setStatus(com.repopilot.common.state.LifecycleStatus.ACTIVE);
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        return agent;
    }

    private EvaluationBatch batch(String experimentId, String planVersionId, int batchNo,
                                  BatchTriggerType triggerType) {
        Instant now = Instant.now();
        EvaluationBatch batch = new EvaluationBatch();
        batch.setId(Ulid.newId());
        batch.setExperimentId(experimentId);
        batch.setEvaluationPlanVersionId(planVersionId);
        batch.setBatchNo(batchNo);
        batch.setTriggerType(triggerType);
        batch.setSelectionJson(json("{\"runIds\":[]}"));
        batch.setStatus(BatchStatus.PENDING);
        batch.setExpectedJobCount(0L);
        batch.setCreatedBy("it");
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        return batch;
    }

    private JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
