package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.reliability.service.OutboxWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.state.JobStatus;
import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.entity.EvaluationJob;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanVersionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测 Job 的创建与读取。
 *
 * <p>TASK-004 只创建 {@code PENDING} Job，<b>不写任何结果</b>：规则引擎在 TASK-007。
 * 前端会显示"等待评测"，而不是一个假分数。
 *
 * <p>幂等由两层保证：先读"已存在的方案项"只补缺失，数据库唯一键
 * {@code (batch_id, run_id, plan_item_id)} 兜底。重复完成回调不会产生第二套 Job。
 */
@Service
public class EvaluationJobService {

    private final EvaluationJobMapper jobMapper;
    private final EvaluationBatchMapper batchMapper;
    private final EvaluationPlanVersionService planVersionService;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    public EvaluationJobService(EvaluationJobMapper jobMapper, EvaluationBatchMapper batchMapper,
                                EvaluationPlanVersionService planVersionService,
                                OutboxWriter outbox,
                                ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.batchMapper = batchMapper;
        this.planVersionService = planVersionService;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 为已封口的 Run 创建本批次的 PENDING Job。
     *
     * @return 新建数量（幂等重放时为 0）
     */
    public int createPendingJobs(String runId, String batchId, String planVersionId,
                                 String acceptedAttemptId) {
        Instant now = Instant.now();
        Set<String> existing = new HashSet<>(jobMapper.listPlanItemIds(batchId, runId));
        List<EvaluationJob> jobs = new ArrayList<>();
        for (EvaluationPlanItem item : planVersionService.listItems(planVersionId)) {
            if (existing.contains(item.getId())) {
                continue;
            }
            EvaluationJob job = new EvaluationJob();
            job.setId(Ulid.newId());
            job.setRunId(runId);
            job.setBatchId(batchId);
            job.setEvaluationPlanVersionId(planVersionId);
            job.setEvaluationPlanItemId(item.getId());
            job.setStatus(JobStatus.PENDING);
            job.setAttemptCount(0);
            job.setInputHash(inputHash(runId, acceptedAttemptId, item));
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            jobs.add(job);
        }
        if (jobs.isEmpty()) {
            return 0;
        }
        // 批次状态推进放在插入之前（TASK-012 压测实测后调整）：
        // evaluation_batches 的 INITIAL 行是该实验下所有 Run 封口都要锁的**热点行**。
        // 原来"先插 Job、再 markRunning"意味着批次行的行锁被持有到整个事务结束——
        // 8 个 Worker 并发封口同一实验的不同 Run 时，这些事务全部排在同一行上，
        // 与 evaluation_jobs 插入的间隙锁交叉，实测必然触发死锁。
        //
        // 同一个事务内调整顺序不改变原子性：要么都成功，要么一起回滚。
        // 但把热点行的锁持有时间从"整批插入"缩短到"一条 UPDATE"，
        // 交叉窗口大幅收窄。批次行本身是唯一热点，别的表按 Run/Job 打散，
        // 不存在第二个全局串行点，所以无需全局锁序或外层重试。
        //
        // 该 UPDATE 幂等且单调（只从 PENDING 出发），重复执行无副作用。
        batchMapper.markRunning(batchId, now);
        jobMapper.batchInsert(jobs);
        // Job 与唤醒通知同事务：Job 创建提交了而通知丢了，评测 Job 会一直 PENDING
        // 直到 Worker 的周期领取兜底——能跑，但白白慢一个轮询周期。
        // 反过来（通知提交、Job 回滚）则会产生指向不存在 Job 的通知，必须避免。
        for (EvaluationJob job : jobs) {
            outbox.writeEvaluationReady(job.getId(), runId);
        }
        return jobs.size();
    }

    /**
     * Job 输入摘要。
     *
     * <p>包含 Run、accepted Attempt 与方案项配置：同一 Run 在两个批次、两个方案版本下
     * 的输入必然不同，摘要也不同。它不包含 Job 自己的 id，否则重放会生成不同摘要。
     */
    private String inputHash(String runId, String acceptedAttemptId, EvaluationPlanItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("runId", runId);
        node.put("acceptedAttemptId", acceptedAttemptId);
        node.put("planVersionId", item.getPlanVersionId());
        node.put("planItemId", item.getId());
        node.put("evaluatorDefinitionId", item.getEvaluatorDefinitionId());
        node.put("executionOrder", item.getExecutionOrder());
        node.put("required", Boolean.TRUE.equals(item.getRequired()));
        JsonNode config = item.getConfigJson();
        node.set("config", config == null ? objectMapper.createObjectNode() : config);
        return Sha256.ofUtf8(CanonicalJson.canonicalize(node));
    }

    public List<JobListRow> listByRun(String runId, String batchId) {
        return jobMapper.listByRun(runId, batchId);
    }

    public int skipPendingByExperiment(String experimentId) {
        return jobMapper.skipPendingByExperiment(experimentId, Instant.now());
    }
}
