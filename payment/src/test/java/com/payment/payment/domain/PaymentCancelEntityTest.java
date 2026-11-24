package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.exception.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentCancelEntityTest {

    @Test
    @DisplayName("취소 시도는 REQUESTED로 생성되고 주문, 멱등키, 사유를 담는다")
    void request() {
        // when
        PaymentCancel attempt = PaymentCancel.request(1L, "k1", "단순 변심");

        // then
        assertThat(attempt.getStatus()).isEqualTo(PaymentCancelStatus.REQUESTED);
        assertThat(attempt.getOrderId()).isEqualTo(1L);
        assertThat(attempt.getIdempotencyKey()).isEqualTo("k1");
        assertThat(attempt.getReason()).isEqualTo("단순 변심");
        assertThat(attempt.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("주문, 멱등키, 사유가 없거나 길이를 넘으면 생성할 수 없다")
    void requestValidation() {
        // when // then
        assertThatThrownBy(() -> PaymentCancel.request(null, "k1", "사유")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCancel.request(1L, " ", "사유")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCancel.request(1L, "k".repeat(101), "사유")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCancel.request(1L, "k1", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCancel.request(1L, "k1", "가".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REQUESTED에서 COMPLETED, FAILED(실패 코드 기록), UNKNOWN으로 갈 수 있다")
    void transitionsFromRequested() {
        // given
        PaymentCancel completed = PaymentCancel.request(1L, "k1", "사유");
        PaymentCancel failed = PaymentCancel.request(1L, "k2", "사유");
        PaymentCancel unknown = PaymentCancel.request(1L, "k3", "사유");

        // when
        completed.complete();
        failed.fail(ErrorCode.PAYMENT_CANCEL_DECLINED);
        unknown.markUnknown();

        // then
        assertThat(completed.getStatus()).isEqualTo(PaymentCancelStatus.COMPLETED);
        assertThat(failed.getStatus()).isEqualTo(PaymentCancelStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("PAYMENT_CANCEL_DECLINED");
        assertThat(unknown.getStatus()).isEqualTo(PaymentCancelStatus.UNKNOWN);
    }

    @Test
    @DisplayName("결과가 반영된 시도는 다시 반영할 수 없다 (늦게 도착한 반영이 결과를 덮어쓰지 않는다)")
    void cannotTransitTwice() {
        // given
        PaymentCancel attempt = PaymentCancel.request(1L, "k1", "사유");
        attempt.complete();

        // when // then
        assertThatThrownBy(attempt::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.fail(ErrorCode.PAYMENT_CANCEL_DECLINED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(attempt::markUnknown).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("실패 코드 없이 실패로 바꿀 수 없다")
    void failRequiresCode() {
        // given
        PaymentCancel attempt = PaymentCancel.request(1L, "k1", "사유");

        // when // then
        assertThatThrownBy(() -> attempt.fail(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문과 사유가 모두 같아야 같은 요청이다")
    void isSameRequest() {
        // given
        PaymentCancel attempt = PaymentCancel.request(1L, "k1", "단순 변심");

        // when // then
        assertThat(attempt.isSameRequest(1L, "단순 변심")).isTrue();
        assertThat(attempt.isSameRequest(1L, "배송 지연")).isFalse();
        assertThat(attempt.isSameRequest(2L, "단순 변심")).isFalse();
    }
}
