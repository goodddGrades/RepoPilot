package com.repopilot.modules.evaluationconfig.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanVersionMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 方案配置读取门面（TASK-010 新增）。
 *
 * <p>报告模块要读 {@code PlanVersion.configJson} 里的门禁规则，但门禁规则是
 * **本模块定义的配置格式**，不该由报告模块去解析一段自己也不拥有schema 的 JSON：
 * 那样规则字段一改，报告与配置模块就要同时改，而且没有单一真相。
 *
 * <p>因此这里只把原始 {@code configJson} 交出去，解析与校验留在本模块的
 * {@link GateRuleReader}；报告只消费已经类型化的规则对象。
 */
@Service
public class PlanVersionConfigQueryService {

    private final EvaluationPlanVersionMapper versionMapper;

    public PlanVersionConfigQueryService(EvaluationPlanVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    public Optional<EvaluationPlanVersion> find(String planVersionId) {
        return versionMapper.findById(planVersionId);
    }

    public EvaluationPlanVersion require(String planVersionId) {
        return versionMapper.findById(planVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "评测方案版本不存在: " + planVersionId));
    }

    /** 方案配置的原始 JSON；缺失时返回 {@code null} 而不是空对象，让调用方能区分"没有配置"。 */
    public JsonNode configOf(String planVersionId) {
        return require(planVersionId).getConfigJson();
    }
}