package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.modules.importing.service.ImportService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线导入用例基类。
 *
 * <p>继承执行测试基类，复用"已发布 Agent/数据集/方案"的构造；额外提供上传原文件、
 * 创建 OFFLINE 实验与触发批次处理的方法。
 *
 * <p>本类**关闭了异步自动处理**（{@code repopilot.import.auto-process=false}）：
 * 用例显式调用 {@link ImportService#resume(String)}，于是"处理前 / 处理后"是确定的状态，
 * 不需要 sleep 撞时间窗口。生产默认仍是异步。
 */
abstract class AbstractOfflineIT extends AbstractExecutionIT {

    @Autowired
    protected ImportService importService;

    /**
     * 父类已按外键顺序清理执行域与导入域（回执 → Run → 批次）。
     * 这里只补一件父类不管的事：导入原文件没有 {@code run_id}，不属于"Run 侧产物"。
     */
    @Override
    @BeforeEach
    void resetExecutionState() {
        super.resetExecutionState();
        jdbc.update("DELETE FROM artifacts WHERE run_id IS NULL");
    }

    // ------------------------------------------------------------------ 造数据

    /** 上传一份 JSONL 原文件，返回 Artifact id。 */
    protected String uploadLines(List<String> lines) {
        return uploadLines(lines, "runs.jsonl");
    }

    protected String uploadLines(List<String> lines, String filename) {
        byte[] content = String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8);
        return uploadBytes(content, filename);
    }

    protected String uploadBytes(byte[] content, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("artifactType", "IMPORT_FILE");
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/artifacts", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().value())
                .as("上传原文件应返回 201: %s", response.getBody()).isEqualTo(201);
        return dataId(response);
    }

    protected String createOfflineExperiment(Scenario scenario, String targetKey, int repeatCount) {
        String suffix = scenario.suffix();
        ResponseEntity<String> response = post("/api/v1/experiments", "it-offline-exp-" + suffix,
                """
                {"name":"离线实验 %s","mode":"OFFLINE","datasetVersionId":"%s",
                 "evaluationPlanVersionId":"%s","config":{},
                 "targets":[{"targetKey":"%s","displayName":"历史对象 A","repeatCount":%d}]}
                """.formatted(suffix, scenario.datasetVersionId(), scenario.planVersionId(),
                targetKey, repeatCount));
        assertThat(response.getStatusCode().value())
                .as("创建离线实验应返回 201: %s", response.getBody()).isEqualTo(201);
        assertThat(data(response).path("mode").asText()).isEqualTo("OFFLINE");
        return dataId(response);
    }

    protected ResponseEntity<String> createImport(String experimentId, String datasetVersionId,
                                                  String artifactId, String idemKey) {
        return post("/api/v1/imports", idemKey,
                """
                {"importType":"OFFLINE_RUNS","datasetVersionId":"%s","experimentId":"%s",
                 "fileArtifactId":"%s"}
                """.formatted(datasetVersionId, experimentId, artifactId));
    }

    /** 同步跑完一个批次；生产里由后台线程做同一件事。 */
    protected void process(String batchId) {
        importService.resume(batchId);
    }

    // ------------------------------------------------------------------ 读状态

    protected JsonNode batch(String batchId) {
        return data(get("/api/v1/imports/" + batchId));
    }

    protected String batchStatus(String batchId) {
        return batch(batchId).path("status").asText();
    }

    protected List<JsonNode> importErrors(String batchId) {
        JsonNode page = data(get("/api/v1/imports/" + batchId + "/errors?page=1&pageSize=100"));
        List<JsonNode> items = new ArrayList<>();
        page.path("items").forEach(items::add);
        return items;
    }

    protected List<String> errorCodes(String batchId) {
        return importErrors(batchId).stream().map(e -> e.path("errorCode").asText()).toList();
    }

    protected long runCount(String experimentId) {
        return count("SELECT COUNT(*) FROM evaluation_runs WHERE experiment_id = ?", experimentId);
    }

    protected long eventCount(String runId) {
        return count("SELECT COUNT(*) FROM trace_events WHERE run_id = ?", runId);
    }

    // ------------------------------------------------------------------ 行构造

    /** 一条规范 JSONL 行；{@code outputJson} 传 {@code null} 表示没有输出。 */
    protected String runLine(String targetKey, String caseKey, int repeatIndex,
                             String sourceRecordId, String outputJson) {
        StringBuilder line = new StringBuilder("{");
        line.append("\"schemaVersion\":1,");
        line.append("\"targetKey\":\"").append(targetKey).append("\",");
        line.append("\"caseKey\":\"").append(caseKey).append("\",");
        line.append("\"repeatIndex\":").append(repeatIndex).append(',');
        line.append("\"sourceSystem\":\"manual\",");
        line.append("\"sourceRecordId\":\"").append(sourceRecordId).append("\",");
        line.append("\"taskOutcome\":\"UNKNOWN\",");
        if (outputJson != null) {
            line.append("\"output\":").append(outputJson).append(',');
        }
        line.append("\"usage\":{\"costKnown\":false},");
        line.append("\"startedAt\":\"2026-10-17T12:00:00.000+08:00\",");
        line.append("\"finishedAt\":\"2026-10-17T12:00:05.000+08:00\",");
        line.append("\"events\":[{\"sourceEventId\":\"evt-1\",\"sequenceNo\":1,");
        line.append("\"eventType\":\"OUTPUT_PRODUCED\",\"occurredAt\":\"2026-10-17T12:00:05.000+08:00\",");
        line.append("\"payload\":{\"outputSummary\":\"ok\"}}]}");
        return line.toString();
    }
}
