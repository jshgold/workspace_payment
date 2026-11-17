package com.payment.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    @DisplayName("버전은 7이고 variant는 RFC 4122/9562 형식이다")
    void versionAndVariant() {
        // when
        UUID uuid = UuidV7.generate();

        // then
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("앞 48비트는 생성 시각(밀리초)이다")
    void embedsTimestamp() {
        // given
        long millis = 1_763_400_000_000L;

        // when
        UUID uuid = UuidV7.generate(millis);

        // then
        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(millis);
    }

    @Test
    @DisplayName("시각이 다르면 문자열 정렬 순서가 생성 순서와 같다")
    void sortableByTime() {
        // when
        String earlier = UuidV7.generate(1_763_400_000_000L).toString();
        String later = UuidV7.generate(1_763_400_000_001L).toString();

        // then
        assertThat(earlier).isLessThan(later);
    }

    @Test
    @DisplayName("같은 밀리초에 여러 번 만들어도 중복되지 않는다")
    void uniqueWithinSameMillis() {
        // given
        Set<UUID> seen = new HashSet<>();

        // when
        for (int i = 0; i < 10_000; i++) {
            seen.add(UuidV7.generate(1_763_400_000_000L));
        }

        // then
        assertThat(seen).hasSize(10_000);
    }

    @Test
    @DisplayName("문자열은 36자라서 토스 orderId 규칙(6~64자, 영문·숫자·-)을 만족한다")
    void satisfiesTossOrderIdRule() {
        // when
        String text = UuidV7.generate().toString();

        // then
        assertThat(text).hasSize(36).matches("[A-Za-z0-9\\-_=]{6,64}");
    }
}
