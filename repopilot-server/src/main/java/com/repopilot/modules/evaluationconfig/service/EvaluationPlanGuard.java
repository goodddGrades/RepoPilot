package com.repopilot.modules.evaluationconfig.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanMapper;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanVersionMapper;
import org.springframework.stereotype.Component;

/**
 * 方案版本的加锁与可写性判定。
 *
 * <p>加锁顺序固定为 <b>方案行 → 版本行</b>。创建版本先锁方案行分配版本号，
 * 编辑草稿若先锁版本行就构成相反顺序，并发时会死锁。
 */
@Component
public class EvaluationPlanGuard {

    private final EvaluationPlanMapper planMapper;
    private final EvaluationPlanVersionMapper versionMapper;

    public EvaluationPlanGuard(EvaluationPlanMapper planMapper,
                               EvaluationPlanVersionMapper versionMapper) {
        this.planMapper = planMapper;
        this.versionMapper = versionMapper;
    }

    public EvaluationPlanVersion requireVersion(String versionId) {
        return versionMapper.findById(versionId).orElseThrow(() -> notFound(versionId));
    }

    public EvaluationPlan requirePlan(String planId) {
        return planMapper.findById(planId).orElseThrow(() -> planNotFound(planId));
    }

    /** 加锁并确认仍是草稿：发布后的 config/items 是历史结果的输入，不能再改。 */
    public EvaluationPlanVersion lockEditable(String versionId) {
        EvaluationPlanVersion locked = lock(versionId);
        if (locked.getStatus() != VersionStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "方案版本不是草稿（当前 " + locked.getStatus() + "），不能修改");
        }
        return locked;
    }

    /** 加锁但不要求草稿：发布与归档自己用状态机判断。 */
    public EvaluationPlanVersion lock(String versionId) {
        EvaluationPlanVersion snapshot = requireVersion(versionId);
        planMapper.findByIdForUpdate(snapshot.getPlanId())
                .orElseThrow(() -> planNotFound(snapshot.getPlanId()));
        return versionMapper.findByIdForUpdate(versionId).orElseThrow(() -> notFound(versionId));
    }

    static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "方案版本不存在: " + id);
    }

    static BusinessException planNotFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评测方案不存在: " + id);
    }
}
