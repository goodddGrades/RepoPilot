package com.repopilot.modules.evaluationconfig.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.EvaluatorType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.mapper.EvaluatorDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 算子定义只读查询。
 *
 * <p>不提供写接口：算子来自随代码版本管理的注册清单，启动时幂等导入。
 * 允许 HTTP 创建/修改算子，等于允许在运行期改变评测语义，历史结果将无法解释。
 */
@Service
public class EvaluatorDefinitionService {

    private final EvaluatorDefinitionMapper mapper;

    public EvaluatorDefinitionService(EvaluatorDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    public PageResponse<EvaluationDtos.EvaluatorView> list(Integer page, Integer pageSize,
                                                            String key, String type, String status) {
        PageQuery query = PageQuery.of(page, pageSize);
        EvaluatorType parsedType = type == null || type.isBlank() ? null : parseType(type);
        VersionStatus parsedStatus = status == null || status.isBlank() ? null : parseStatus(status);
        String normalizedKey = key == null || key.isBlank() ? null : key.trim();

        List<EvaluationDtos.EvaluatorView> items = mapper
                .search(normalizedKey, parsedType, parsedStatus, query.pageSize(), query.offset())
                .stream().map(EvaluationDtos.EvaluatorView::from).toList();
        return PageResponse.of(items, query, mapper.count(normalizedKey, parsedType, parsedStatus));
    }

    public EvaluationDtos.EvaluatorView get(String id) {
        return mapper.findById(id).map(EvaluationDtos.EvaluatorView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "算子定义不存在: " + id));
    }

    private EvaluatorType parseType(String value) {
        try {
            return EvaluatorType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("type", "不支持的 type: " + value);
        }
    }

    private VersionStatus parseStatus(String value) {
        try {
            return VersionStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
    }
}
