package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.payment.payment.domain.PaymentClient;
import com.payment.payment.service.PaymentStateService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 결제 시도로 기록할 수 없는 형식 오류(필수 값 없음)는 트랜잭션 전에 거른다. */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeInputTest {

    @Mock
    PaymentStateService paymentStateService;

    @Mock
    PaymentClient paymentClient;

    @Test
    @DisplayName("결제 키가 없거나 공백이면 결제를 시작하지 않고 PG도 호출하지 않는다")
    void blankPaymentKeyIsRejectedBeforeStartPaying() {
        // given
        PaymentFacade paymentFacade = new PaymentFacade(paymentStateService, paymentClient);

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(1L, "key-1", 20_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentFacade.approve(1L, "key-1", 20_000L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentStateService, paymentClient);
    }

    @Test
    @DisplayName("멱등키가 없거나 공백이면 결제를 시작하지 않고 PG도 호출하지 않는다")
    void blankIdempotencyKeyIsRejectedBeforeStartPaying() {
        // given
        PaymentFacade paymentFacade = new PaymentFacade(paymentStateService, paymentClient);

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(1L, null, 20_000L, "payment-key-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentFacade.approve(1L, " ", 20_000L, "payment-key-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentStateService, paymentClient);
    }
}
