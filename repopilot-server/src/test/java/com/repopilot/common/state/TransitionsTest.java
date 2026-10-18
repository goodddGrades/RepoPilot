package com.repopilot.common.state;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机纯规则测试：穷举关键路径，并确认终态不可回退。
 */
class TransitionsTest {

    @Test
    void experimentHappyPathShouldBeAllowed() {
        assertThat(Transitions.allows(ExperimentStatus.DRAFT, ExperimentStatus.QUEUED)).isTrue();
        assertThat(Transitions.allows(ExperimentStatus.QUEUED, ExperimentStatus.RUNNING)).isTrue();
        assertThat(Transitions.allows(ExperimentStatus.RUNNING, ExperimentStatus.COMPLETED)).isTrue();
        assertThat(Transitions.allows(ExperimentStatus.RUNNING, ExperimentStatus.PARTIAL_FAILED)).isTrue();
    }

    @Test
    void experimentMustNotSkipQueuedOrRevertFromTerminal() {
        assertThat(Transitions.allows(ExperimentStatus.DRAFT, ExperimentStatus.RUNNING)).isFalse();
        assertThat(Transitions.allows(ExperimentStatus.COMPLETED, ExperimentStatus.RUNNING)).isFalse();
        assertThat(Transitions.allows(ExperimentStatus.CANCELLED, ExperimentStatus.DRAFT)).isFalse();
    }

    @Test
    void onlineRunShouldPassThroughQueuedWhileOfflineMustNot() {
        assertThat(Transitions.allows(RunStatus.PENDING, RunStatus.QUEUED, RunMode.ONLINE)).isTrue();
        assertThat(Transitions.allows(RunStatus.PENDING, RunStatus.QUEUED, RunMode.OFFLINE)).isFalse();

        assertThat(Transitions.allows(RunStatus.PENDING, RunStatus.EVALUATING, RunMode.OFFLINE)).isTrue();
        assertThat(Transitions.allows(RunStatus.PENDING, RunStatus.EVALUATING, RunMode.ONLINE)).isFalse();
    }

    @Test
    void anyNonTerminalRunShouldBeCancellable() {
        assertThat(Transitions.allows(RunStatus.QUEUED, RunStatus.CANCELLED, RunMode.ONLINE)).isTrue();
        assertThat(Transitions.allows(RunStatus.RUNNING, RunStatus.CANCELLED, RunMode.ONLINE)).isTrue();
        assertThat(Transitions.allows(RunStatus.EVALUATING, RunStatus.CANCELLED, RunMode.OFFLINE)).isTrue();
        assertThat(Transitions.allows(RunStatus.COMPLETED, RunStatus.CANCELLED, RunMode.ONLINE)).isFalse();
    }

    @Test
    void jobShouldNotReturnFromTerminalBecauseRetryAddsAttempt() {
        assertThat(Transitions.allows(JobStatus.PENDING, JobStatus.RUNNING)).isTrue();
        assertThat(Transitions.allows(JobStatus.RUNNING, JobStatus.FAILED)).isTrue();
        assertThat(Transitions.allows(JobStatus.FAILED, JobStatus.RUNNING)).isFalse();
        assertThat(Transitions.allows(JobStatus.COMPLETED, JobStatus.RUNNING)).isFalse();
        assertThat(Transitions.allows(JobStatus.PENDING, JobStatus.SKIPPED)).isTrue();
    }

    @Test
    void batchShouldEndInFailedOnlyFromRunning() {
        assertThat(Transitions.allows(BatchStatus.PENDING, BatchStatus.RUNNING)).isTrue();
        assertThat(Transitions.allows(BatchStatus.RUNNING, BatchStatus.PARTIAL_FAILED)).isTrue();
        assertThat(Transitions.allows(BatchStatus.PENDING, BatchStatus.COMPLETED)).isFalse();
        assertThat(Transitions.allows(BatchStatus.COMPLETED, BatchStatus.RUNNING)).isFalse();
    }

    @Test
    void annotationReturnShouldGoBackToClaimed() {
        assertThat(Transitions.allows(AnnotationTaskStatus.SUBMITTED, AnnotationTaskStatus.RETURNED)).isTrue();
        assertThat(Transitions.allows(AnnotationTaskStatus.RETURNED, AnnotationTaskStatus.CLAIMED)).isTrue();
        assertThat(Transitions.allows(AnnotationTaskStatus.REVIEWED, AnnotationTaskStatus.CLAIMED)).isFalse();
    }

    @Test
    void publishedVersionMustNotGoBackToDraft() {
        assertThat(Transitions.allows(VersionStatus.DRAFT, VersionStatus.PUBLISHED)).isTrue();
        assertThat(Transitions.allows(VersionStatus.PUBLISHED, VersionStatus.ARCHIVED)).isTrue();
        assertThat(Transitions.allows(VersionStatus.PUBLISHED, VersionStatus.DRAFT)).isFalse();
        assertThat(Transitions.allows(VersionStatus.ARCHIVED, VersionStatus.PUBLISHED)).isFalse();
    }

    @Test
    void draftShouldBeArchivableButArchivedStaysTerminal() {
        // 2026-10-18 裁决：草稿可以丢弃（归档），归档后不可回到任何可写状态
        assertThat(Transitions.allows(VersionStatus.DRAFT, VersionStatus.ARCHIVED)).isTrue();
        assertThat(Transitions.allows(VersionStatus.ARCHIVED, VersionStatus.DRAFT)).isFalse();
        assertThat(Transitions.allows(VersionStatus.ARCHIVED, VersionStatus.PUBLISHED)).isFalse();
        assertThat(Transitions.allows(VersionStatus.ARCHIVED, VersionStatus.ARCHIVED)).isFalse();
    }

    @Test
    void requireShouldThrowConflictWithSpecificErrorCode() {
        assertThatThrownBy(() -> Transitions.require(ExperimentStatus.COMPLETED, ExperimentStatus.RUNNING))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }
}
