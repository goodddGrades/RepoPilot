package com.repopilot.modules.experiment.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import org.springframework.stereotype.Service;

/**
 * 实验状态的只读查询。
 *
 * <p>单独成服务是为了打断依赖环：执行侧（trace）需要判断"实验是否已取消"，
 * 而实验服务本身依赖执行侧生成槽位。这个类只有读方法，不参与任何业务写入，
 * 因而不会形成 "experiment → trace → experiment → …" 的构造环。
 */
@Service
public class ExperimentStatusService {

    private final ExperimentMapper experimentMapper;

    public ExperimentStatusService(ExperimentMapper experimentMapper) {
        this.experimentMapper = experimentMapper;
    }

    public Experiment require(String id) {
        return experimentMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + id));
    }

    public boolean isCancelled(String id) {
        return experimentMapper.findById(id)
                .map(e -> e.getStatus() == ExperimentStatus.CANCELLED)
                .orElse(true);
    }
}
