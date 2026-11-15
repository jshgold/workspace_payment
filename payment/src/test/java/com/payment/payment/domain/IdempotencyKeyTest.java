package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    @DisplayName("멱등키를 만들면 IN_PROGRESS이고, 성공이나 실패로 한 번만 확정할 수 있다")
    void lifecycle() {
        // given
        IdempotencyKey succeeded = IdempotencyKey.create("key-1", 1L);
        IdempotencyKey failed = IdempotencyKey.create("key-2", 1L);
        assertThat(succeeded.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);

        // when
        succeeded.succeed();
        failed.fail("PAYMENT_DECLINED");

        // then
        assertThat(succeeded.getStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(failed.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("PAYMENT_DECLINED");
        assertThatThrownBy(succeeded::succeed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.fail("X")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 키, 100자 초과 키, 주문 ID 없음은 만들 수 없다")
    void createFailsWithInvalidArguments() {
        // when // then
        assertThatThrownBy(() -> IdempotencyKey.create(" ", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.create("k".repeat(101), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.create("key", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키가 묶인 주문인지 확인할 수 있다")
    void isOwnedBy() {
        // given
        IdempotencyKey key = IdempotencyKey.create("key-1", 1L);

        // when // then
        assertThat(key.isOwnedBy(1L)).isTrue();
        assertThat(key.isOwnedBy(2L)).isFalse();
    }
}
