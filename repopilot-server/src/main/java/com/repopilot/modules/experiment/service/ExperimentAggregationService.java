package com.repopilot.modules.experiment.service;

import com.repopilot.common.reliability.service.OutboxWriter;
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
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public ExperimentAggregationService(ExperimentMapper experimentMapper, RunStore runStore,
                                        OutboxWriter outbox,
                                        PlatformTransactionManager transactionManager) {
        this.experimentMapper = experimentMapper;
        this.runStore = runStore;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 记录「Run 进入终态」并尝试收敛实验。
     *
     * <p><b>为什么 Run 终态通知单独一个入口</b>：Run 有三条进入终态的路径
     * （成功封口、失败重试耗尽、租约回收），而 {@link #aggregateIfSettled} 只在
     * 整个实验收口时才触发。在三条路径各写一遍通知会漏掉其中一条，而漏掉的后果是
     * 「前端看不到这个 Run 结束了」——这类问题只在特定失败路径出现，极难排查。
     * 收在这里，一处不漏。
     *
     * <p>通知与状态写在同一事务：Run 终态回滚了，通知也一起消失。
     */
    public void onRunTerminal(String runId, String experimentId) {
        tx.executeWithoutResult(status -> outbox.writeRunFinished(runId, experimentId));
        aggregateIfSettled(experimentId);
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
                // 状态与通知同事务：这里回滚了通知也一起消失，不会出现"实验没结束却通知结束"
                outbox.writeExperimentFinished(experimentId, experimentId);
                log.info("实验 {} 聚合为 {}（完成 {} / 共 {}）", experimentId, target, completed, total);
            }
        });
    }
}
