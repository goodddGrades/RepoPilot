package com.repopilot.modules.experiment.service;

import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.JobStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.service.EvaluationJobService;
import com.repopilot.modules.trace.dto.RunDtos;
import com.repopilot.modules.trace.dto.RunListRow;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.service.RunStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 的读取视图。
 *
 * <p>只读，不改变任何执行状态。Job 摘要按批次分组：同一 Run 可能出现在多个批次（重评），
 * 把它们混在一起会让"这个 Run 到底评完没有"无法回答。
 */
@Service
public class RunQueryService {

    private final RunStore runStore;
    private final ExperimentStatusService experimentStatus;
    private final EvaluationJobService jobService;
    private final com.repopilot.modules.evaluation.mapper.EvaluationResultMapper resultMapper;
    private final com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper jobAttemptMapper;

    public RunQueryService(RunStore runStore, ExperimentStatusService experimentStatus,
                           EvaluationJobService jobService,
                           com.repopilot.modules.evaluation.mapper.EvaluationResultMapper resultMapper,
                           com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper jobAttemptMapper) {
        this.runStore = runStore;
        this.experimentStatus = experimentStatus;
        this.jobService = jobService;
        this.resultMapper = resultMapper;
        this.jobAttemptMapper = jobAttemptMapper;
    }

    public PageResponse<RunDtos.RunView> list(String experimentId, String targetId, String caseKey,
                                              String status, String taskOutcome,
                                              Integer page, Integer pageSize) {
        experimentStatus.require(experimentId);
        PageQuery query = PageQuery.of(page, pageSize);
        RunStatus parsedStatus = status == null || status.isBlank() ? null : parseRunStatus(status);
        TaskOutcome parsedOutcome = taskOutcome == null || taskOutcome.isBlank()
                ? null : parseTaskOutcome(taskOutcome);
        List<RunDtos.RunView> items = runStore
                .search(experimentId, targetId, caseKey, parsedStatus, parsedOutcome,
                        query.pageSize(), query.offset())
                .stream().map(RunDtos.RunView::from).toList();
        long total = runStore.count(experimentId, targetId, caseKey, parsedStatus, parsedOutcome);
        return PageResponse.of(items, query, total);
    }

    public RunDtos.RunDetail detail(String runId) {
        EvaluationRun run = runStore.requireRun(runId);
        RunListRow row = new RunListRow();
        row.id = run.getId();
        row.experimentId = run.getExperimentId();
        row.experimentTargetId = run.getExperimentTargetId();
        row.datasetCaseId = run.getDatasetCaseId();
        row.repeatIndex = run.getRepeatIndex();
        row.mode = run.getMode();
        row.status = run.getStatus();
        row.taskOutcome = run.getTaskOutcome();
        row.attemptCount = run.getAttemptCount();
        row.acceptedAttemptId = run.getAcceptedAttemptId();
        row.sealedAt = run.getSealedAt();
        row.startedAt = run.getStartedAt();
        row.finishedAt = run.getFinishedAt();
        row.createdAt = run.getCreatedAt();

        List<RunDtos.AttemptView> attempts = runStore.attempts(runId, 100, 0)
                .stream().map(RunDtos.AttemptView::from).toList();
        List<JobListRow> jobs = jobService.listByRun(runId, null);
        List<RunDtos.JobView> jobViews = toJobViews(jobs);
        // 默认看 accepted Attempt；执行中则看当前 active Attempt（与 TASK-006 的默认读取口径一致）
        String eventAttemptId = run.getAcceptedAttemptId() != null
                ? run.getAcceptedAttemptId() : run.getActiveAttemptId();
        return new RunDtos.RunDetail(RunDtos.RunView.from(row),
                run.getSourceSystem(), run.getSourceRecordId(),
                run.getOutputJson(),
                run.getOutputArtifactId(), run.getUsageJson(), run.getObservationMetadataJson(),
                runStore.countEventsOfRun(runId, eventAttemptId),
                attempts, summarize(jobs), jobViews);
    }

    public RunDtos.JobSummaryView jobs(String runId, String batchId) {
        runStore.requireRun(runId);
        List<JobListRow> jobs = jobService.listByRun(runId, batchId);
        return new RunDtos.JobSummaryView(summarize(jobs), toJobViews(jobs));
    }

    private List<RunDtos.JobView> toJobViews(List<JobListRow> jobs) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        Map<String, RunDtos.JobResultView> results = new LinkedHashMap<>();
        List<String> jobIds = jobs.stream().map(j -> j.id).toList();
        for (com.repopilot.modules.evaluation.entity.EvaluationResult result
                : resultMapper.findByJobIds(jobIds)) {
            results.put(result.getJobId(), new RunDtos.JobResultView(result.getId(),
                    result.getJobAttemptId(), result.getVerdict(), result.getScore(),
                    result.getReason(), result.getEvidenceJson(), result.getResultJson(),
                    result.getGeneratedBy(), result.getCreatedAt()));
        }
        List<RunDtos.JobView> views = new ArrayList<>(jobs.size());
        for (JobListRow j : jobs) {
            List<RunDtos.JobAttemptView> attempts = jobAttemptMapper.listByJob(j.id).stream()
                    .map(a -> new RunDtos.JobAttemptView(a.getId(), a.getAttemptNo(), a.getWorkerId(),
                            a.getStatus(), a.getStartedAt(), a.getFinishedAt(),
                            a.getErrorCode(), a.getErrorMessage()))
                    .toList();
            views.add(new RunDtos.JobView(j.id, j.batchId, j.batchNo == null ? 0 : j.batchNo,
                    j.evaluatorKey, j.evaluatorVersion == null ? 0 : j.evaluatorVersion,
                    j.evaluatorType,
                    j.executionOrder == null ? 0 : j.executionOrder,
                    Boolean.TRUE.equals(j.required), j.status,
                    j.attemptCount == null ? 0 : j.attemptCount, j.errorCode, j.errorMessage,
                    j.startedAt, j.finishedAt, results.get(j.id), attempts));
        }
        return views;
    }

    private List<RunDtos.JobBatchSummary> summarize(List<JobListRow> jobs) {
        Map<String, BatchAccumulator> map = new LinkedHashMap<>();
        for (JobListRow job : jobs) {
            BatchAccumulator acc = map.computeIfAbsent(job.batchId,
                    id -> new BatchAccumulator(id, job.batchNo == null ? 0 : job.batchNo,
                            job.evaluationPlanVersionId));
            acc.total++;
            if (job.status == JobStatus.PENDING) {
                acc.pending++;
            } else if (job.status == JobStatus.RUNNING) {
                acc.running++;
            } else if (job.status == JobStatus.COMPLETED) {
                acc.completed++;
            } else if (job.status == JobStatus.FAILED) {
                acc.failed++;
            } else if (job.status == JobStatus.SKIPPED) {
                acc.skipped++;
            }
        }
        List<RunDtos.JobBatchSummary> result = new ArrayList<>();
        for (BatchAccumulator acc : map.values()) {
            result.add(new RunDtos.JobBatchSummary(acc.batchId, acc.batchNo, acc.planVersionId,
                    acc.total, acc.pending, acc.running, acc.completed, acc.failed, acc.skipped));
        }
        return result;
    }

    private static final class BatchAccumulator {
        private final String batchId;
        private final int batchNo;
        private final String planVersionId;
        private int total;
        private int pending;
        private int running;
        private int completed;
        private int failed;
        private int skipped;

        private BatchAccumulator(String batchId, int batchNo, String planVersionId) {
            this.batchId = batchId;
            this.batchNo = batchNo;
            this.planVersionId = planVersionId;
        }
    }

    public PageResponse<RunDtos.AttemptView> attempts(String runId, Integer page, Integer pageSize) {
        runStore.requireRun(runId);
        PageQuery query = PageQuery.of(page, pageSize);
        List<RunDtos.AttemptView> items = runStore.attempts(runId, query.pageSize(), query.offset())
                .stream().map(RunDtos.AttemptView::from).toList();
        return PageResponse.of(items, query, runStore.countAttempts(runId));
    }

    private static RunStatus parseRunStatus(String value) {
        try {
            return RunStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
    }

    private static TaskOutcome parseTaskOutcome(String value) {
        try {
            return TaskOutcome.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("taskOutcome", "不支持的 taskOutcome: " + value);
        }
    }
}
