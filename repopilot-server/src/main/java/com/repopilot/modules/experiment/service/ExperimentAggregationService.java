package com.repopilot.modules.experiment.service;

import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.Transitions;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.trace.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 实验技术状态聚合。
 *
 * <p>只在**没有非终态 Run** 时才尝试收口。TASK-004 的正常路径是 Run 停在 EVALUATING
 * 等待算子（TASK-007），因此这里大多数时候不会触发；它的作用是覆盖"全部 Run 都执行失败"
 * 这种确实已经结束的情况，而不是提前宣布成功。
 *
 * <p>条件更新（{@code WHERE status = 读到的旧值}）保证不会把并发取消的实验改回 FAILED：
 * 取消先提交则影响行数为 0，反之取消自己的条件更新会失败并返回 409。
 */
@Service
public class ExperimentAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentAggregationService.class);

    private final ExperimentMapper experimentMapper;
    private final RunStore runStore;
    private final TransactionTemplate tx;

    public ExperimentAggregationService(ExperimentMapper experimentMapper, RunStore runStore,
                                        PlatformTransactionManager transactionManager) {
        this.experimentMapper = experimentMapper;
        this.runStore = runStore;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 尝试收敛实验状态。
     *
     * <p>调用方通常是"Run 刚进入终态"的回调路径。若仍有 Run 在执行或等待评测，直接返回，
     * 不做任何写入。
     */
    public void aggregateIfSettled(String experimentId) {
        tx.executeWithoutResult(status -> {
            long nonTerminal = runStore.countNonTerminal(experimentId);
            if (nonTerminal > 0) {
                return;
            }
            long total = runStore.countByExperiment(experimentId);
            if (total == 0) {
                return;
            }
            ExperimentStatus current = experimentMapper.findById(experimentId)
                    .map(e -> e.getStatus()).orElse(null);
            if (current != ExperimentStatus.RUNNING) {
                // QUEUED 说明还没有任务被领走，不该有 Run 进入终态；DRAFT/终态/取消都不再改写
                return;
            }
            long completed = runStore.countCompleted(experimentId);
            ExperimentStatus target = completed == total
                    ? ExperimentStatus.COMPLETED
                    : completed > 0 ? ExperimentStatus.PARTIAL_FAILED : ExperimentStatus.FAILED;
            if (!Transitions.allows(current, target)) {
                // 状态机不允许的收敛一律不做：宁可留在 RUNNING 等人工处理，也不写非法状态
                log.warn("实验 {} 从 {} 收敛到 {} 不被状态机允许，跳过聚合", experimentId, current, target);
                return;
            }
            Instant now = Instant.now();
            int updated = experimentMapper.updateStatus(experimentId, current, target, now, now);
            if (updated == 1) {
                log.info("实验 {} 聚合为 {}（完成 {} / 共 {}）", experimentId, target, completed, total);
            }
        });
    }
}
