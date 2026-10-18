package com.repopilot.modules.evaluationconfig.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.state.EvaluatorType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;
import com.repopilot.modules.evaluationconfig.mapper.EvaluatorDefinitionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 算子注册清单导入。
 *
 * <p>清单 {@code classpath:seed/evaluators.json} 随代码版本管理，启动时幂等导入：
 * 以 {@code (evaluator_key, version)} 判定是否已存在，存在就跳过。
 *
 * <p><b>已存在但内容不同时只告警，不覆盖。</b>已发布的算子定义是历史结果的解释依据，
 * 静默改写会让"同一版本两个行为"——那样的结果无法复盘。要改契约就发新版本。
 *
 * <p>导入失败直接让启动失败：算子清单缺项时服务"看起来正常但评测全 NOT_APPLICABLE"，
 * 比启动失败更难发现。
 */
@Component
public class EvaluatorSeedImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorSeedImporter.class);

    private static final String RESOURCE = "seed/evaluators.json";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final EvaluatorDefinitionMapper mapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public EvaluatorSeedImporter(EvaluatorDefinitionMapper mapper, ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        JsonNode root = readResource();
        JsonNode list = root.get("evaluators");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " 中没有任何算子定义");
        }
        int imported = tx.execute(status -> importAll(list));
        log.info("算子注册清单已处理：{} 条，新增 {} 条", list.size(), imported);
    }

    private int importAll(JsonNode list) {
        int inserted = 0;
        for (int i = 0; i < list.size(); i++) {
            JsonNode node = list.get(i);
            String where = "evaluators[" + i + "]";
            EvaluatorDefinition definition = toEntity(node, where);

            Optional<EvaluatorDefinition> existing =
                    mapper.findByKeyAndVersion(definition.getEvaluatorKey(), definition.getVersion());
            if (existing.isPresent()) {
                if (differs(existing.get(), definition)) {
                    log.warn("算子定义 {} v{} 已存在且内容不同，保留库内版本（改契约请发布新版本）",
                            definition.getEvaluatorKey(), definition.getVersion());
                }
                continue;
            }
            mapper.insert(definition);
            inserted++;
        }
        return inserted;
    }

    private EvaluatorDefinition toEntity(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new IllegalStateException(where + " 必须是对象");
        }
        String key = text(node, "key", where);
        if (key.length() > 64) {
            throw new IllegalStateException(where + ".key 超过 64 字符");
        }
        int version = node.path("version").asInt(0);
        if (version < 1) {
            throw new IllegalStateException(where + ".version 必须 >= 1");
        }
        String type = text(node, "type", where);
        EvaluatorType evaluatorType;
        try {
            evaluatorType = EvaluatorType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ".type 不支持: " + type);
        }

        Instant now = Instant.now();
        EvaluatorDefinition entity = new EvaluatorDefinition();
        entity.setId(Ulid.newId());
        entity.setEvaluatorKey(key);
        entity.setVersion(version);
        entity.setDisplayName(text(node, "displayName", where));
        entity.setDescription(node.path("description").asText(null));
        entity.setEvaluatorType(evaluatorType);
        entity.setImplementationRef(text(node, "implementationRef", where));
        entity.setInputSchemaJson(requireObject(node, "inputSchema", where));
        entity.setOutputSchemaJson(requireObject(node, "outputSchema", where));
        // 清单里的算子即为 V1 可用算子：直接发布，否则任何方案版本都无法发布
        entity.setStatus(VersionStatus.PUBLISHED);
        entity.setPublishedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private boolean differs(EvaluatorDefinition existing, EvaluatorDefinition seeded) {
        return !Objects.equals(existing.getDisplayName(), seeded.getDisplayName())
                || existing.getEvaluatorType() != seeded.getEvaluatorType()
                || !Objects.equals(existing.getImplementationRef(), seeded.getImplementationRef())
                || !sameJson(existing.getInputSchemaJson(), seeded.getInputSchemaJson())
                || !sameJson(existing.getOutputSchemaJson(), seeded.getOutputSchemaJson());
    }

    private boolean sameJson(JsonNode left, JsonNode right) {
        return CanonicalJson.canonicalize(left).equals(CanonicalJson.canonicalize(right));
    }

    private JsonNode readResource() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            int schemaVersion = root.path("schemaVersion").asInt(0);
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw new IllegalStateException(RESOURCE + " 的 schemaVersion 必须是 "
                        + SUPPORTED_SCHEMA_VERSION);
            }
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取 " + RESOURCE, ex);
        }
    }

    private String text(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(where + "." + field + " 不能为空");
        }
        return value.textValue();
    }

    private JsonNode requireObject(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(where + "." + field + " 必须是 JSON 对象");
        }
        return value;
    }
}
