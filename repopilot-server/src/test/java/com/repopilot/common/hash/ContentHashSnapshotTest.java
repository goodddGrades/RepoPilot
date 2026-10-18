package com.repopilot.common.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.service.DatasetContentHasher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容摘要快照测试。
 *
 * <p>这里断言的是**写死的 hash 值**。它存在的意义不是"证明算法正确"，而是：
 * 一旦有人改了规范化实现（换了键序、改了标度、加了转义），历史版本的 hash 就再也复现不出来，
 * 而这些改动通常不会让任何功能报错——只有这个测试会红。
 *
 * <p>期望值可以用任何遵守同一规范的工具独立算出（例如 Python 的
 * {@code json.dumps(sort_keys=True, separators=(',',':'), ensure_ascii=False)}），
 * 所以它不是"用实现证明实现"。
 */
class ContentHashSnapshotTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();
    private static final ObjectMapper DECIMAL = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final DatasetContentHasher hasher = new DatasetContentHasher(PLAIN);

    @Test
    void datasetCasesHashShouldMatchSnapshot() throws Exception {
        DatasetCase first = datasetCase("case-001", "AGENT_TASK",
                "{\"prompt\":\"说 hello\"}", null, "{\"tags\":[\"a\",\"b\"]}");
        DatasetCase second = datasetCase("case-002", "LLM_CALL",
                "{\"prompt\":\"1.0 与 0.10\"}", "{\"answer\":\"ok\"}", null);

        assertThat(hasher.hashCases(List.of(first, second)))
                .isEqualTo("120c695346df125ed7f078af658a0a3bc219c552aa08df4c0a9fecba4dc66989");
    }

    @Test
    void datasetCasesHashShouldIgnoreInputOrder() throws Exception {
        DatasetCase first = datasetCase("case-001", "AGENT_TASK",
                "{\"prompt\":\"说 hello\"}", null, "{\"tags\":[\"a\",\"b\"]}");
        DatasetCase second = datasetCase("case-002", "LLM_CALL",
                "{\"prompt\":\"1.0 与 0.10\"}", "{\"answer\":\"ok\"}", null);

        assertThat(hasher.hashCases(List.of(second, first)))
                .isEqualTo(hasher.hashCases(List.of(first, second)));
    }

    @Test
    void missingExpectedAndExplicitNullShouldHashTheSame() throws Exception {
        DatasetCase missing = datasetCase("case-001", "AGENT_TASK", "{\"p\":1}", null, null);
        DatasetCase explicitNull = datasetCase("case-001", "AGENT_TASK", "{\"p\":1}", "null", null);

        assertThat(hasher.hashCases(List.of(missing)))
                .isEqualTo(hasher.hashCases(List.of(explicitNull)));
    }

    @Test
    void planHashShouldMatchSnapshot() throws Exception {
        var config = PLAIN.readTree("{\"maxRetries\":3}");
        var itemConfig = DECIMAL.readTree("{\"threshold\":0.5}");

        String hash = hasher.hashPlan(config, List.of(
                new DatasetContentHasher.PlanItemView("01J000000000000000000000E1", 1, true, itemConfig)));

        assertThat(hash).isEqualTo("b82c44859bda1ff7a36e97d1c4f6b4e78c229547c4af992efb670bc65eb3e2cf");
    }

    @Test
    void configHashShouldPreserveDecimalScale() throws Exception {
        // 注意这里的期望值：0.10 必须保持两位小数。用 Python 直接 dumps 会得到 0.1，
        // 所以快照值是按"保留标度"的规范手工构造字符串算出来的，不是照抄某个库的输出。
        var config = CanonicalJson.readTree("{\"b\":1.0,\"a\":{\"z\":[1,2],\"y\":\"中文\"},\"c\":0.10}");
        assertThat(hasher.hashConfig(config))
                .isEqualTo("37f92edd5b27cc6ad74914f169858f59036dd01b24e83df5571b0fdf01a44243");
    }

    private DatasetCase datasetCase(String key, String taskType, String input,
                                    String expected, String metadata) throws Exception {
        DatasetCase entity = new DatasetCase();
        entity.setId("01J0000000000000000000000" + key.charAt(key.length() - 1));
        entity.setDatasetVersionId("01J000000000000000000000V1");
        entity.setCaseKey(key);
        entity.setTaskType(taskType);
        entity.setInputJson(PLAIN.readTree(input));
        entity.setExpectedJson(expected == null ? null : PLAIN.readTree(expected));
        entity.setMetadataJson(metadata == null ? null : PLAIN.readTree(metadata));
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
