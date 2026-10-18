package com.repopilot.common.api;

import com.repopilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    @Test
    void shouldApplyContractDefaults() {
        PageQuery query = PageQuery.of(null, null);

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(20);
        assertThat(query.offset()).isZero();
    }

    @Test
    void offsetShouldFollowPageNumber() {
        assertThat(PageQuery.of(3, 20).offset()).isEqualTo(40);
        assertThat(PageQuery.of(1, 100).offset()).isZero();
    }

    @Test
    void outOfRangeValuesShouldFailFastInsteadOfBeingClamped() {
        assertThatThrownBy(() -> PageQuery.of(0, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page");
        assertThatThrownBy(() -> PageQuery.of(1, 101))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> PageQuery.of(1, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pageResponseShouldCopyItemsAndKeepTotals() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b"), PageQuery.of(2, 2), 7L);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(7L);
        assertThat(PageResponse.empty(PageQuery.of(1, 20)).items()).isEmpty();
    }
}
