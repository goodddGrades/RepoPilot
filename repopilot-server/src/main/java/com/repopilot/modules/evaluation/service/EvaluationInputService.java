package com.repopilot.modules.evaluation.service;

import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import com.repopilot.modules.evaluation.dto.EvaluationJobRow;
import com.repopilot.modules.experiment.dto.WorkerDtos;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.service.RunStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 评测输入快照。
 *
 * <p>这里只读**已封口**的数据：accepted Attempt 的输出与事件、Run 自己冻结的样本。
 * 三条约束写在代码里而不是注释里：
 * <ul>
 *   <li>在线读 {@code accepted_attempt_id} 的事件，不合并失败重试的 Trace；</li>
 *   <li>离线 Run 没有 Attempt，事件按 {@code run_id} 读，{@code acceptedAttemptId} 保持 null；</li>
 *   <li>轨迹清单摘要与事件数一起下发，Worker 读到不一致时必须报技术错误，
 *       不允许"少几条也能算"。</li>
 * </ul>
 */
@Service
public class EvaluationInputService {

    /** Worker 单次读取的事件上限来自 TASK-006 的公开游标接口（最大 500）。 */
    private static final int EVENT_PAGE_LIMIT = 200;

    private final RunStore runStore;
    private final DatasetVersionService datasetVersionService;

    public EvaluationInputService(RunStore runStore, DatasetVersionService datasetVersionService) {
        this.runStore = runStore;
        this.datasetVersionService = datasetVersionService;
    }

    public WorkerDtos.EvaluationInput build(EvaluationJobRow job, EvaluationRun run) {
        DatasetCase datasetCase = datasetVersionService.requireCase(run.getDatasetCaseId());
        WorkerDtos.CasePayload casePayload = new WorkerDtos.CasePayload(datasetCase.getId(),
                datasetCase.getCaseKey(), datasetCase.getTaskType(), datasetCase.getInputJson(),
                datasetCase.getExpectedJson(), datasetCase.getMetadataJson());

        String acceptedAttemptId = run.getAcceptedAttemptId();
        List<String> eventIds = new ArrayList<>(runStore.eventIdsOfRun(run.getId(), acceptedAttemptId));

        String eventsUrl = "/api/v1/runs/" + run.getId() + "/events?limit=" + EVENT_PAGE_LIMIT
                + (acceptedAttemptId == null ? "" : "&attemptId=" + acceptedAttemptId);
        WorkerDtos.TraceSnapshot trace = new WorkerDtos.TraceSnapshot(run.getSealedAt() != null,
                eventsUrl, eventIds.size(), manifestSha(eventIds));

        WorkerDtos.RunSnapshot snapshot = new WorkerDtos.RunSnapshot(
                run.getId(),
                run.getMode() == null ? RunMode.ONLINE.name() : run.getMode().name(),
                (run.getTaskOutcome() == null ? TaskOutcome.UNKNOWN : run.getTaskOutcome()).name(),
                acceptedAttemptId,
                run.getOutputJson(),
                run.getOutputArtifactId(),
                run.getUsageJson(),
                casePayload,
                trace);

        return new WorkerDtos.EvaluationInput("1", job.jobId, job.batchId,
                job.executionOrder == null ? 0 : job.executionOrder,
                Boolean.TRUE.equals(job.required),
                new WorkerDtos.EvaluatorPayload(job.evaluatorKey,
                        job.evaluatorVersion == null ? 0 : job.evaluatorVersion,
                        job.evaluatorType == null ? null : job.evaluatorType.name(),
                        job.implementationRef),
                job.configJson,
                snapshot);
    }

    /** 与 TASK-004 完成回调同一算法：eventId 按 ASCII 升序，LF 连接，末尾无 LF。 */
    public static String manifestSha(List<String> eventIds) {
        List<String> sorted = new ArrayList<>(eventIds);
        sorted.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256", ex);
        }
    }
}
