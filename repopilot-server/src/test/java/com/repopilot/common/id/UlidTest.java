package com.repopilot.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UlidTest {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    void shouldGenerateTwentySixUppercaseCrockfordCharacters() {
        String id = Ulid.newId();

        assertThat(id).hasSize(26);
        assertThat(id.chars().mapToObj(c -> String.valueOf((char) c)))
                .allSatisfy(c -> assertThat(ALPHABET).contains(c));
        assertThat(id.charAt(0)).isLessThanOrEqualTo('7');
    }

    @Test
    void shouldBeUniqueAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> assertThat(seen.add(Ulid.newId())).isTrue());
    }

    @Test
    void sameMillisecondIdsShouldStayStrictlyIncreasing() {
        // 同一毫秒内靠随机段进位保证单调：否则批量插入会得到随机顺序，按 id 分页会漏行
        List<String> ids = IntStream.range(0, 50)
                .mapToObj(i -> Ulid.generate(1_700_000_000_000L))
                .toList();

        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i).compareTo(ids.get(i - 1))).isPositive();
        }
    }

    @Test
    void laterTimestampShouldSortAfterEarlierTimestamp() {
        String earlier = Ulid.generate(1_700_000_000_000L);
        String later = Ulid.generate(1_700_000_001_000L);

        assertThat(later.compareTo(earlier)).isPositive();
    }

    @Test
    void shouldRejectMalformedValues() {
        assertThat(Ulid.isValid(null)).isFalse();
        assertThat(Ulid.isValid("")).isFalse();
        assertThat(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA")).isFalse();
        assertThat(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV1")).isFalse();
        // I/L/O/U 不在 Crockford 字母表内
        assertThat(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAI")).isFalse();
        assertThat(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAO")).isFalse();
        // 首位超过 7 表示 48 位时间戳溢出
        assertThat(Ulid.isValid("81ARZ3NDEKTSV4RRFFQ69G5FAV")).isFalse();
        assertThat(Ulid.isValid("-1ARZ3NDEKTSV4RRFFQ69G5FAV")).isFalse();
    }

    @Test
    void requireValidShouldNormalizeCaseAndRejectInvalid() {
        String id = Ulid.newId();

        assertThat(Ulid.requireValid(id.toLowerCase())).isEqualTo(id);
        assertThatThrownBy(() -> Ulid.requireValid("bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的 ULID");
    }
}
