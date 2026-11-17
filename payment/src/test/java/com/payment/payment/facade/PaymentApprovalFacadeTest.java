package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.IdempotencyService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** 결과 종류별로 멱등키에 무엇을 기록하는지(확정 실패만 FAILED, 모르면 IN_PROGRESS 유지) 분기만 검증한다. */
@ExtendWith(MockitoExtension.class)
class PaymentApprovalFacadeTest {

    private static final Long ORDER_ID = 1L;
    private static final String KEY = "key-1";
    private static final long AMOUNT = 20_000L;
    private static final PaymentResponse RESPONSE =
            new PaymentResponse(ORDER_ID, 10L, "pg-tx-1", AMOUNT, OrderStatus.PAID);

    @Mock
    IdempotencyService idempotencyService;

    @Mock
    PaymentFacade paymentFacade;

    PaymentApprovalFacade facade;

    @BeforeEach
    void setUp() {
        facade = new PaymentApprovalFacade(idempotencyService, paymentFacade);
    }

    @Test
    @DisplayName("처음 온 요청이 성공하면 키를 SUCCEEDED로 기록하고 결과를 돌려준다")
    void success() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1")).willReturn(RESPONSE);

        // when
        PaymentResponse response = facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1");

        // then
        assertThat(response).isEqualTo(RESPONSE);
        verify(idempotencyService).succeed(KEY);
    }

    @Test
    @DisplayName("같은 키의 재요청이면 PG 승인 흐름을 진행하지 않고 첫 결과를 돌려준다")
    void replay() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.of(RESPONSE));

        // when
        PaymentResponse response = facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1");

        // then
        assertThat(response).isEqualTo(RESPONSE);
        verify(paymentFacade, never()).approve(any(), any());
        verify(idempotencyService, never()).succeed(any());
    }

    @Test
    @DisplayName("PG 거절은 돈이 나가지 않은 확정 실패라서 키를 FAILED로 기록한다")
    void declinedIsRecordedAsFailure() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1")).willThrow(new PaymentDeclinedException("한도 초과"));

        // when // then
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1")).isInstanceOf(PaymentDeclinedException.class);
        verify(idempotencyService).fail(KEY, ErrorCode.PAYMENT_DECLINED);
    }

    @Test
    @DisplayName("주문 상태 충돌이나 재고 부족 같은 IllegalStateException도 확정 실패로 기록한다")
    void stateConflictIsRecordedAsFailure() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1")).willThrow(new IllegalStateException("결제 중"));

        // when // then
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1")).isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).fail(KEY, ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("PG 타임아웃은 결과를 모르므로 키를 확정하지 않고 IN_PROGRESS로 남긴다")
    void timeoutLeavesKeyInProgress() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1")).willThrow(new PaymentTimeoutException());

        // when // then
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1")).isInstanceOf(PaymentTimeoutException.class);
        verify(idempotencyService, never()).fail(any(), any());
        verify(idempotencyService, never()).succeed(any());
    }

    @Test
    @DisplayName("보상 취소 실패와 무결성 위반도 결과를 모르므로 키를 확정하지 않는다")
    void unknownOutcomesLeaveKeyInProgress() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1"))
                .willThrow(new PaymentCancelFailedException("pg-tx-1"))
                .willThrow(new DataIntegrityViolationException("Duplicate entry"));

        // when // then
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1")).isInstanceOf(PaymentCancelFailedException.class);
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1")).isInstanceOf(DataIntegrityViolationException.class);
        verify(idempotencyService, never()).fail(any(), any());
    }

    @Test
    @DisplayName("결제는 성공했는데 키 성공 기록이 실패해도 사용자에게는 성공 결과를 돌려준다")
    void successRecordFailureDoesNotHideSuccess() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT)).willReturn(Optional.empty());
        given(paymentFacade.approve(ORDER_ID, "payment-key-1")).willReturn(RESPONSE);
        willThrow(new IllegalStateException("기록 실패")).given(idempotencyService).succeed(KEY);

        // when
        PaymentResponse response = facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1");

        // then
        assertThat(response).isEqualTo(RESPONSE);
    }

    @Test
    @DisplayName("키 선점 단계의 예외(처리 중, 금액 불일치 등)는 그대로 전파하고 PG를 호출하지 않는다")
    void beginFailurePropagates() {
        // given
        given(idempotencyService.begin(KEY, ORDER_ID, AMOUNT))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS));

        // when // then
        assertThatThrownBy(() -> facade.approve(ORDER_ID, KEY, AMOUNT, "payment-key-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
        verify(paymentFacade, never()).approve(any(), any());
    }
}
