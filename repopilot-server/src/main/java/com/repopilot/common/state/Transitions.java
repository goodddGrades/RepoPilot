package com.repopilot.common.state;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态转换规则（对应 {@code docs/architecture/06-reliability-design.md} 的"允许的转换"）。
 *
 * <p>这是纯规则：不查库、不依赖 Spring，输入两个状态输出是否允许，因此可以被穷举测试。
 * 真正的并发安全不靠这里，而靠数据库条件更新（`UPDATE ... WHERE status = 期望旧值` 并检查影响行数）
 * 与租约校验；本类只负责"业务上这一步是否合法"。
 *
 * <p>非法转换统一抛 {@link ErrorCode#INVALID_STATE_TRANSITION}（409），
 * 不使用泛化错误码，便于调用方区分"状态不允许"与"资源冲突"。
 */
public final class Transitions {

    private static final Map<ExperimentStatus, EnumSet<ExperimentStatus>> EXPERIMENT =
            new EnumMap<>(ExperimentStatus.class);
    private static final Map<RunStatus, EnumSet<RunStatus>> RUN_ONLINE =
            new EnumMap<>(RunStatus.class);
    private static final Map<RunStatus, EnumSet<RunStatus>> RUN_OFFLINE =
            new EnumMap<>(RunStatus.class);
    private static final Map<JobStatus, EnumSet<JobStatus>> JOB =
            new EnumMap<>(JobStatus.class);
    private static final Map<BatchStatus, EnumSet<BatchStatus>> BATCH =
            new EnumMap<>(BatchStatus.class);
    private static final Map<AttemptStatus, EnumSet<AttemptStatus>> ATTEMPT =
            new EnumMap<>(AttemptStatus.class);
    private static final Map<AnnotationTaskStatus, EnumSet<AnnotationTaskStatus>> ANNOTATION =
            new EnumMap<>(AnnotationTaskStatus.class);
    private static final Map<VersionStatus, EnumSet<VersionStatus>> VERSION =
            new EnumMap<>(VersionStatus.class);
    private static final Map<LifecycleStatus, EnumSet<LifecycleStatus>> LIFECYCLE =
            new EnumMap<>(LifecycleStatus.class);

    static {
        // Experiment：提交阶段校验失败保持 DRAFT；暂无 Worker 保持 QUEUED，不额外造状态
        allow(EXPERIMENT, ExperimentStatus.DRAFT, ExperimentStatus.QUEUED);
        allow(EXPERIMENT, ExperimentStatus.QUEUED, ExperimentStatus.RUNNING);
        allow(EXPERIMENT, ExperimentStatus.RUNNING, ExperimentStatus.COMPLETED,
                ExperimentStatus.PARTIAL_FAILED, ExperimentStatus.FAILED);
        allow(EXPERIMENT, ExperimentStatus.DRAFT, ExperimentStatus.CANCELLED);
        allow(EXPERIMENT, ExperimentStatus.QUEUED, ExperimentStatus.CANCELLED);
        allow(EXPERIMENT, ExperimentStatus.RUNNING, ExperimentStatus.CANCELLED);

        // 在线 Run：PENDING→QUEUED→RUNNING→EVALUATING→COMPLETED
        allow(RUN_ONLINE, RunStatus.PENDING, RunStatus.QUEUED);
        allow(RUN_ONLINE, RunStatus.QUEUED, RunStatus.RUNNING);
        allow(RUN_ONLINE, RunStatus.RUNNING, RunStatus.EVALUATING);
        allow(RUN_ONLINE, RunStatus.EVALUATING, RunStatus.COMPLETED);
        // 离线 Run 没有执行阶段：PENDING→EVALUATING→COMPLETED，且不产生 RunAttempt
        allow(RUN_OFFLINE, RunStatus.PENDING, RunStatus.EVALUATING);
        allow(RUN_OFFLINE, RunStatus.EVALUATING, RunStatus.COMPLETED);
        for (Map<RunStatus, EnumSet<RunStatus>> table : java.util.List.of(RUN_ONLINE, RUN_OFFLINE)) {
            allow(table, RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMED_OUT);
            allow(table, RunStatus.EVALUATING, RunStatus.FAILED);
            for (RunStatus from : RunStatus.values()) {
                if (!isRunTerminal(from)) {
                    allow(table, from, RunStatus.CANCELLED);
                }
            }
        }

        // Job：重试保持 RUNNING 并追加 Attempt，因此 COMPLETED/FAILED 不再回到 RUNNING
        allow(JOB, JobStatus.PENDING, JobStatus.RUNNING);
        allow(JOB, JobStatus.RUNNING, JobStatus.COMPLETED, JobStatus.FAILED);
        allow(JOB, JobStatus.PENDING, JobStatus.SKIPPED);

        allow(BATCH, BatchStatus.PENDING, BatchStatus.RUNNING);
        allow(BATCH, BatchStatus.RUNNING, BatchStatus.COMPLETED,
                BatchStatus.PARTIAL_FAILED, BatchStatus.FAILED);
        allow(BATCH, BatchStatus.PENDING, BatchStatus.CANCELLED);
        allow(BATCH, BatchStatus.RUNNING, BatchStatus.CANCELLED);

        allow(ATTEMPT, AttemptStatus.RUNNING, AttemptStatus.COMPLETED,
                AttemptStatus.FAILED, AttemptStatus.TIMED_OUT, AttemptStatus.CANCELLED);

        allow(ANNOTATION, AnnotationTaskStatus.PENDING, AnnotationTaskStatus.CLAIMED);
        allow(ANNOTATION, AnnotationTaskStatus.RETURNED, AnnotationTaskStatus.CLAIMED);
        allow(ANNOTATION, AnnotationTaskStatus.CLAIMED, AnnotationTaskStatus.SUBMITTED);
        allow(ANNOTATION, AnnotationTaskStatus.SUBMITTED, AnnotationTaskStatus.REVIEWED);
        allow(ANNOTATION, AnnotationTaskStatus.SUBMITTED, AnnotationTaskStatus.RETURNED);
        allow(ANNOTATION, AnnotationTaskStatus.PENDING, AnnotationTaskStatus.CANCELLED);
        allow(ANNOTATION, AnnotationTaskStatus.CLAIMED, AnnotationTaskStatus.CANCELLED);
        allow(ANNOTATION, AnnotationTaskStatus.SUBMITTED, AnnotationTaskStatus.CANCELLED);
        allow(ANNOTATION, AnnotationTaskStatus.RETURNED, AnnotationTaskStatus.CANCELLED);

        // 版本发布不可逆：DRAFT→PUBLISHED→ARCHIVED，不支持回到 DRAFT
        allow(VERSION, VersionStatus.DRAFT, VersionStatus.PUBLISHED);
        // 2026-10-18 裁决：允许丢弃建错的草稿；ARCHIVED 为终态，不可回到 DRAFT/PUBLISHED
        allow(VERSION, VersionStatus.DRAFT, VersionStatus.ARCHIVED);
        allow(VERSION, VersionStatus.PUBLISHED, VersionStatus.ARCHIVED);

        allow(LIFECYCLE, LifecycleStatus.ACTIVE, LifecycleStatus.ARCHIVED);
    }

    private Transitions() {
    }

    private static <E extends Enum<E>> void allow(Map<E, EnumSet<E>> table, E from, E... to) {
        EnumSet<E> targets = table.get(from);
        if (targets == null) {
            targets = EnumSet.noneOf(from.getDeclaringClass());
            table.put(from, targets);
        }
        targets.addAll(java.util.List.of(to));
    }

    private static boolean isRunTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
                || status == RunStatus.TIMED_OUT || status == RunStatus.CANCELLED;
    }

    public static boolean allows(ExperimentStatus from, ExperimentStatus to) {
        return allows(EXPERIMENT, from, to);
    }

    public static boolean allows(RunStatus from, RunStatus to, RunMode mode) {
        return allows(mode == RunMode.OFFLINE ? RUN_OFFLINE : RUN_ONLINE, from, to);
    }

    public static boolean allows(JobStatus from, JobStatus to) {
        return allows(JOB, from, to);
    }

    public static boolean allows(BatchStatus from, BatchStatus to) {
        return allows(BATCH, from, to);
    }

    public static boolean allows(AttemptStatus from, AttemptStatus to) {
        return allows(ATTEMPT, from, to);
    }

    public static boolean allows(AnnotationTaskStatus from, AnnotationTaskStatus to) {
        return allows(ANNOTATION, from, to);
    }

    public static boolean allows(VersionStatus from, VersionStatus to) {
        return allows(VERSION, from, to);
    }

    public static boolean allows(LifecycleStatus from, LifecycleStatus to) {
        return allows(LIFECYCLE, from, to);
    }

    public static void require(ExperimentStatus from, ExperimentStatus to) {
        requireAllows(allows(from, to), "Experiment", from, to);
    }

    public static void require(RunStatus from, RunStatus to, RunMode mode) {
        requireAllows(allows(from, to, mode), "EvaluationRun(" + mode + ")", from, to);
    }

    public static void require(JobStatus from, JobStatus to) {
        requireAllows(allows(from, to), "EvaluationJob", from, to);
    }

    public static void require(BatchStatus from, BatchStatus to) {
        requireAllows(allows(from, to), "EvaluationBatch", from, to);
    }

    public static void require(AttemptStatus from, AttemptStatus to) {
        requireAllows(allows(from, to), "Attempt", from, to);
    }

    public static void require(AnnotationTaskStatus from, AnnotationTaskStatus to) {
        requireAllows(allows(from, to), "AnnotationTask", from, to);
    }

    public static void require(VersionStatus from, VersionStatus to) {
        requireAllows(allows(from, to), "Version", from, to);
    }

    private static void requireAllows(boolean allowed, String aggregate, Enum<?> from, Enum<?> to) {
        if (!allowed) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    aggregate + " 不允许从 " + from + " 转换为 " + to);
        }
    }

    private static <E extends Enum<E>> boolean allows(Map<E, EnumSet<E>> table, E from, E to) {
        Set<E> targets = table.get(from);
        return targets != null && targets.contains(to);
    }
}
