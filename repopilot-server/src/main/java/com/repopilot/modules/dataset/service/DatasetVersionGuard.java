package com.repopilot.modules.dataset.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.dataset.entity.Dataset;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import com.repopilot.modules.dataset.mapper.DatasetMapper;
import com.repopilot.modules.dataset.mapper.DatasetVersionMapper;
import org.springframework.stereotype.Component;

/**
 * 数据集版本的加锁与可写性判定。
 *
 * <p>抽出来的原因很实际：发布、归档、单条样本增删改、JSONL 导入四条路径都要
 * "先锁数据集行、再锁版本行、确认草稿"，抄四份迟早有一份写错顺序。
 * 加锁顺序必须统一为 <b>数据集行 → 版本行</b>，
 * 否则"创建版本"（先锁数据集）与"编辑样本"（若先锁版本）会互相等成死锁。
 */
@Component
public class DatasetVersionGuard {

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;

    public DatasetVersionGuard(DatasetMapper datasetMapper, DatasetVersionMapper versionMapper) {
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
    }

    /** 无锁读取；不存在直接 404。 */
    public DatasetVersion requireVersion(String versionId) {
        return versionMapper.findById(versionId).orElseThrow(() -> notFound(versionId));
    }

    /**
     * 加锁并校验"父数据集可用、版本仍为草稿"。
     *
     * <p>数据集归档后其下版本不可再编辑——归档语义是"整体停用"，
     * 只读历史不受影响，写入一律拒绝。
     */
    public DatasetVersion lockEditable(String versionId) {
        DatasetVersion locked = lock(versionId);
        if (locked.getStatus() != VersionStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "数据集版本不是草稿（当前 " + locked.getStatus() + "），样本不可修改");
        }
        return locked;
    }

    /** 加锁但不要求草稿：发布与归档自己用状态机判断。 */
    public DatasetVersion lock(String versionId) {
        DatasetVersion snapshot = requireVersion(versionId);
        Dataset dataset = datasetMapper.findByIdForUpdate(snapshot.getDatasetId())
                .orElseThrow(() -> notFound(snapshot.getDatasetId()));
        if (dataset.getStatus() != LifecycleStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "数据集已归档，不能再修改其版本");
        }
        return versionMapper.findByIdForUpdate(versionId)
                .orElseThrow(() -> notFound(versionId));
    }

    public static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "数据集版本不存在: " + id);
    }
}
