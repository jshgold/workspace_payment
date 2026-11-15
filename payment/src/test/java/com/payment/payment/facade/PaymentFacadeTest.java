package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.PaymentStateService;
import com.payment.payment.service.PaymentTarget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** 실패 종류별로 Facade가 어떤 후속 조치를 하는지(되돌림, 보상 취소, 유지) 분기만 검증한다. */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final Long ORDER_ID = 1L;
    private static final long AMOUNT = 20_000L;
    private static final PgApproveResult APPROVED = new PgApproveResult("pg-tx-1", AMOUNT);

    @Mock
    PaymentStateService paymentStateService;

    @Mock
    PaymentClient paymentClient;

    PaymentFacade paymentFacade;

    @BeforeEach
    void setUp() {
        paymentFacade = new PaymentFacade(paymentStateService, paymentClient);
        given(paymentStateService.startPaying(ORDER_ID)).willReturn(new PaymentTarget(ORDER_ID, AMOUNT));
    }

    @Test
    @DisplayName("정상 승인이면 서버 금액으로 PG를 호출하고 결과를 반영하며 보상이나 되돌림은 없다")
    void approveSuccess() {
        // given
        PaymentResponse expected = new PaymentResponse(ORDER_ID, 10L, "pg-tx-1", AMOUNT, OrderStatus.PAID);
        given(paymentClient.approve(new PgApproveRequest(ORDER_ID, AMOUNT))).willReturn(APPROVED);
        given(paymentStateService.complete(ORDER_ID, APPROVED)).willReturn(expected);

        // when
        PaymentResponse response = paymentFacade.approve(ORDER_ID);

        // then
        assertThat(response).isEqualTo(expected);
        verify(paymentClient, never()).cancel(anyString(), anyString());
        verify(paymentStateService, never()).revertToPending(any());
    }

    @Test
    @DisplayName("PG가 거절하면 PENDING으로 되돌리고 결과 반영과 PG 취소는 하지 않는다")
    void declined() {
        // given
        given(paymentClient.approve(any())).willThrow(new PaymentDeclinedException("한도 초과"));

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID)).isInstanceOf(PaymentDeclinedException.class);
        verify(paymentStateService).revertToPending(ORDER_ID);
        verify(paymentStateService, never()).complete(any(), any());
        verify(paymentClient, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("PG가 타임아웃이면 승인 여부를 모르므로 PAYING을 유지하고 되돌리거나 취소하지 않는다")
    void timeout() {
        // given
        given(paymentClient.approve(any())).willThrow(new PaymentTimeoutException());

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID)).isInstanceOf(PaymentTimeoutException.class);
        verify(paymentStateService, never()).revertToPending(any());
        verify(paymentStateService, never()).complete(any(), any());
        verify(paymentClient, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("승인 금액이 서버 금액과 다르면 승인을 취소하고 PENDING으로 되돌린 뒤 결과를 반영하지 않는다")
    void amountMismatch() {
        // given
        given(paymentClient.approve(any())).willReturn(new PgApproveResult("pg-tx-1", AMOUNT - 1));

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
        verify(paymentClient).cancel("pg-tx-1", "승인 금액 불일치");
        verify(paymentStateService).revertToPending(ORDER_ID);
        verify(paymentStateService, never()).complete(any(), any());
    }

    @Test
    @DisplayName("승인 후 결과 반영이 실패하면 PG 승인을 취소하고 PENDING으로 되돌린다")
    void completeFailsThenCompensate() {
        // given
        given(paymentClient.approve(any())).willReturn(APPROVED);
        given(paymentStateService.complete(ORDER_ID, APPROVED)).willThrow(new IllegalStateException("재고가 부족합니다."));

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID)).isInstanceOf(IllegalStateException.class);
        verify(paymentClient).cancel("pg-tx-1", "결제 반영 실패");
        verify(paymentStateService).revertToPending(ORDER_ID);
    }

    @Test
    @DisplayName("보상 취소마저 실패하면 PAYING을 유지한 채 취소 실패 예외를 던진다")
    void compensationFails() {
        // given
        given(paymentClient.approve(any())).willReturn(APPROVED);
        given(paymentStateService.complete(ORDER_ID, APPROVED)).willThrow(new IllegalStateException("재고가 부족합니다."));
        willThrow(new PaymentCancelFailedException("pg-tx-1")).given(paymentClient).cancel(anyString(), anyString());

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID)).isInstanceOf(PaymentCancelFailedException.class);
        verify(paymentStateService, never()).revertToPending(any());
    }

    @Test
    @DisplayName("무결성 위반은 이미 처리된 결제일 수 있으므로 PG 취소를 하지 않는다")
    void integrityViolationDoesNotCancel() {
        // given
        given(paymentClient.approve(any())).willReturn(APPROVED);
        given(paymentStateService.complete(ORDER_ID, APPROVED))
                .willThrow(new DataIntegrityViolationException("Duplicate entry"));

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(ORDER_ID)).isInstanceOf(DataIntegrityViolationException.class);
        verify(paymentClient, never()).cancel(anyString(), anyString());
        verify(paymentStateService, never()).revertToPending(any());
    }
}
