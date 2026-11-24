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
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgCancelResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.CancelTarget;
import com.payment.payment.service.PaymentCancelStateService;
import com.payment.payment.service.StartCancelCommand;
import com.payment.payment.service.StartCancelResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

/**
 * 취소 오케스트레이션의 분기(PG 결과 → 어느 반영 메서드를 부르는가)를 검증한다. DB 상태는 {@code PaymentCancelTest}에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentCancelFacadeTest {

    private static final Long ORDER_ID = 1L;
    private static final String KEY = "k1";
    private static final String REASON = "단순 변심";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final CancelTarget TARGET = new CancelTarget(10L, ORDER_ID, PAYMENT_KEY);
    private static final StartCancelCommand COMMAND = new StartCancelCommand(ORDER_ID, KEY, REASON);
    private static final PaymentResponse CANCELED =
            new PaymentResponse(ORDER_ID, 5L, PAYMENT_KEY, 20_000L, OrderStatus.CANCELED, PaymentStatus.CANCELED);

    @Mock
    private PaymentCancelStateService stateService;

    @Mock
    private PaymentClient paymentClient;

    @InjectMocks
    private PaymentCancelFacade facade;

    @BeforeEach
    void setUp() {
        given(stateService.startCanceling(COMMAND)).willReturn(new StartCancelResult.Proceed(TARGET));
    }

    @Test
    @DisplayName("PG 취소가 성공하면 completeCancel로 반영하고 그 결과를 돌려준다")
    void success() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON)).willReturn(PgCancelResult.canceled());
        given(stateService.completeCancel(TARGET)).willReturn(CANCELED);

        // when
        PaymentResponse response = facade.cancel(ORDER_ID, KEY, REASON);

        // then
        assertThat(response).isEqualTo(CANCELED);
        verify(stateService, never()).failCancel(any(), any());
        verify(stateService, never()).markCancelUnknown(any());
    }

    @Test
    @DisplayName("PG 취소는 성공했지만 결과 반영(DB)이 실패하면 예외를 그대로 던지고, 실패나 결과 모름으로 기록하지 않는다 (CANCELING으로 남아 대사 대상)")
    void completeFailsAfterPgSuccess() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON)).willReturn(PgCancelResult.canceled());
        given(stateService.completeCancel(TARGET)).willThrow(new QueryTimeoutException("DB 응답 없음"));

        // when // then
        assertThatThrownBy(() -> facade.cancel(ORDER_ID, KEY, REASON)).isInstanceOf(QueryTimeoutException.class);
        verify(stateService, never()).failCancel(any(), any());
        verify(stateService, never()).markCancelUnknown(any());
    }

    @Test
    @DisplayName("PG가 거절하면 PAYMENT_CANCEL_DECLINED로 실패를 기록하고 거절 예외를 던진다")
    void declined() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON))
                .willThrow(new PaymentCancelDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));

        // when // then
        assertThatThrownBy(() -> facade.cancel(ORDER_ID, KEY, REASON)).isInstanceOf(PaymentCancelDeclinedException.class);
        verify(stateService).failCancel(TARGET, ErrorCode.PAYMENT_CANCEL_DECLINED);
        verify(stateService, never()).completeCancel(any());
    }

    @Test
    @DisplayName("요청이 PG에 닿지 않으면 PAYMENT_CANCEL_GATEWAY_ERROR로 실패를 기록하고 같은 코드로 응답한다")
    void gatewayError() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON)).willThrow(new PaymentGatewayException("CONNECTION_FAILED"));

        // when // then
        assertThatThrownBy(() -> facade.cancel(ORDER_ID, KEY, REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR));
        verify(stateService).failCancel(TARGET, ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
    }

    @Test
    @DisplayName("결과를 모르면 결과 모름으로 기록하고 PAYMENT_CANCEL_UNKNOWN으로 응답한다. 실패로 기록하지 않는다")
    void unknown() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON)).willThrow(new PaymentTimeoutException());

        // when // then
        assertThatThrownBy(() -> facade.cancel(ORDER_ID, KEY, REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_UNKNOWN));
        verify(stateService).markCancelUnknown(TARGET);
        verify(stateService, never()).failCancel(any(), any());
    }

    @Test
    @DisplayName("실패 기록 자체가 실패해도 사용자에게는 원래 PG 거절을 돌려준다")
    void failRecordErrorDoesNotHideCause() {
        // given
        given(paymentClient.cancel(PAYMENT_KEY, REASON))
                .willThrow(new PaymentCancelDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));
        willThrow(new QueryTimeoutException("DB 응답 없음")).given(stateService).failCancel(TARGET, ErrorCode.PAYMENT_CANCEL_DECLINED);

        // when // then
        assertThatThrownBy(() -> facade.cancel(ORDER_ID, KEY, REASON)).isInstanceOf(PaymentCancelDeclinedException.class);
    }

    @Test
    @DisplayName("선점 INSERT가 같은 키 UNIQUE에 걸리면 그 키의 저장된 결과로 답하고 PG를 부르지 않는다")
    void duplicateKeyOnInsertIsReplayed() {
        // given
        given(stateService.startCanceling(COMMAND)).willThrow(new DataIntegrityViolationException("uk_payment_cancel_idempotency_key"));
        given(stateService.replay(COMMAND)).willReturn(Optional.of(new StartCancelResult.Replayed(CANCELED)));

        // when
        PaymentResponse response = facade.cancel(ORDER_ID, KEY, REASON);

        // then
        assertThat(response).isEqualTo(CANCELED);
        verify(paymentClient, never()).cancel(any(), any());
    }
}
