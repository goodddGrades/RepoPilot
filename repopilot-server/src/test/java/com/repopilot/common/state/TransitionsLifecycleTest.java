package com.repopilot.common.state;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TASK-003 的归档语义：生命周期只允许 ACTIVE→ARCHIVED，归档后不可复活。 */
class TransitionsLifecycleTest {

    @Test
    void activeShouldBeArchivable() {
        assertThatCode(() -> Transitions.require(LifecycleStatus.ACTIVE, LifecycleStatus.ARCHIVED))
                .doesNotThrowAnyException();
    }

    @Test
    void archivedShouldNotGoBackToActive() {
        assertThat(Transitions.allows(LifecycleStatus.ARCHIVED, LifecycleStatus.ACTIVE)).isFalse();
        assertThatThrownBy(() -> Transitions.require(LifecycleStatus.ARCHIVED, LifecycleStatus.ACTIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void archivedToArchivedShouldBeRejected() {
        // 幂等归档不在这里放行：重复归档应当由 Service 用状态判断处理，
        // 而不是让状态机接受"原地转换"，否则无法区分"动作发生了"与"什么都没发生"。
        assertThat(Transitions.allows(LifecycleStatus.ARCHIVED, LifecycleStatus.ARCHIVED)).isFalse();
    }
}
