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

/** 주문을 선점하기 전에 걸러야 하는 입력 오류. 선점 뒤에 실패하면 PAYING이 남기 때문이다. */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeInputTest {

    @Mock
    PaymentStateService paymentStateService;

    @Mock
    PaymentClient paymentClient;

    @Test
    @DisplayName("결제 키가 없거나 공백이면 주문을 선점하지 않고 PG도 호출하지 않는다")
    void blankPaymentKeyIsRejectedBeforeStartPaying() {
        // given
        PaymentFacade paymentFacade = new PaymentFacade(paymentStateService, paymentClient);

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> paymentFacade.approve(1L, " ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentStateService, paymentClient);
    }
}
