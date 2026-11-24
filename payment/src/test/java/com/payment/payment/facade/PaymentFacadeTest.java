package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.PaymentStateService;
import com.payment.payment.service.PaymentTarget;
import com.payment.payment.service.StartPayingCommand;
import com.payment.payment.service.StartPayingResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 결제 시작 결과와 PG 결과 종류별로 Facade가 어떤 후속 조치(반영, 실패 확정, 결과 모름, 보상 취소)를 하는지 분기만 검증한다.
 * 상태와 재고가 실제로 어떻게 남는지는 {@code PaymentFlowTestSupport}에서 실제 DB로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final Long ORDER_ID = 1L;
    private static final long AMOUNT = 20_000L;
    private static final String KEY = "key-1";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final StartPayingCommand COMMAND = new StartPayingCommand(ORDER_ID, KEY, AMOUNT, PAYMENT_KEY);
    private static final PgApproveResult APPROVED = new PgApproveResult(PAYMENT_KEY, AMOUNT);
    private static final PaymentResponse PAID = new PaymentResponse(
            ORDER_ID, 10L, PAYMENT_KEY, AMOUNT, OrderStatus.PAID, PaymentStatus.APPROVED);

    @Mock
    PaymentStateService paymentStateService;

    @Mock
    PaymentClient paymentClient;

    private PaymentResponse approve() {
        return new PaymentFacade(paymentStateService, paymentClient).approve(ORDER_ID, KEY, AMOUNT, PAYMENT_KEY);
    }

    @Nested
    @DisplayName("PG를 부르기 전에 끝나는 경우")
    class BeforePg {

        @Test
        @DisplayName("같은 키로 이미 승인된 시도의 재전송이면 PG를 부르지 않고 첫 결과를 돌려준다")
        void replayed() {
            // given
            given(paymentStateService.startPaying(COMMAND)).willReturn(new StartPayingResult.Replayed(PAID));

            // when
            PaymentResponse response = approve();

            // then
            assertThat(response).isEqualTo(PAID);
            verifyNoInteractions(paymentClient);
        }

        @Test
        @DisplayName("결제 시작에서 거절(재고 부족 등)이 기록되면 그 에러 코드로 실패하고 PG를 부르지 않는다")
        void rejectedBeforePg() {
            // given
            given(paymentStateService.startPaying(COMMAND))
                    .willReturn(new StartPayingResult.Rejected(ErrorCode.STOCK_NOT_AVAILABLE));

            // when // then
            assertThatThrownBy(() -> approve())
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_AVAILABLE));
            verifyNoInteractions(paymentClient);
        }
    }

    @Nested
    @DisplayName("PG를 부른 경우")
    class WithPg {

        private void startPayingProceeds() {
            given(paymentStateService.startPaying(COMMAND))
                    .willReturn(new StartPayingResult.Proceed(new PaymentTarget(ORDER_ID, "order-no-1", AMOUNT)));
        }

        @Test
        @DisplayName("정상 승인이면 서버 금액으로 PG를 호출하고 결과를 반영하며 실패 기록이나 보상은 없다")
        void approveSuccess() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(new PgApproveRequest("order-no-1", PAYMENT_KEY, AMOUNT))).willReturn(APPROVED);
            given(paymentStateService.complete(ORDER_ID, APPROVED)).willReturn(PAID);

            // when
            PaymentResponse response = approve();

            // then
            assertThat(response).isEqualTo(PAID);
            verify(paymentClient, never()).cancel(anyString(), anyString());
            verify(paymentStateService, never()).fail(any(), any(), any());
            verify(paymentStateService, never()).markUnknown(any(), any(), any());
        }

        @Test
        @DisplayName("PG가 거절하면 PAYMENT_DECLINED로 실패를 확정하고 반영·취소는 하지 않는다")
        void declined() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willThrow(new PaymentDeclinedException("한도 초과"));

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(PaymentDeclinedException.class);
            verify(paymentStateService).fail(ORDER_ID, ErrorCode.PAYMENT_DECLINED, null);
            verify(paymentStateService, never()).complete(any(), any());
            verify(paymentClient, never()).cancel(anyString(), anyString());
        }

        @Test
        @DisplayName("요청이 PG에 닿지 않은 연동 오류면 돈이 나가지 않았으므로 PAYMENT_GATEWAY_ERROR로 실패를 확정한다")
        void gatewayError() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willThrow(new PaymentGatewayException("UNAUTHORIZED_KEY"));

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(PaymentGatewayException.class);
            verify(paymentStateService).fail(ORDER_ID, ErrorCode.PAYMENT_GATEWAY_ERROR, null);
            verify(paymentStateService, never()).complete(any(), any());
        }

        @Test
        @DisplayName("PG가 타임아웃이면 승인 여부를 모르므로 결과 모름으로 남기고 실패 확정·취소는 하지 않는다")
        void timeout() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willThrow(new PaymentTimeoutException());

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(PaymentTimeoutException.class);
            verify(paymentStateService).markUnknown(ORDER_ID, null, null);
            verify(paymentStateService, never()).fail(any(), any(), any());
            verify(paymentClient, never()).cancel(anyString(), anyString());
        }

        @Test
        @DisplayName("승인 금액이 서버 금액과 다르면 승인을 취소하고, 취소가 성공하면 승인 금액과 함께 실패를 확정한다")
        void amountMismatch() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willReturn(new PgApproveResult(PAYMENT_KEY, AMOUNT - 1));

            // when // then
            assertThatThrownBy(() -> approve())
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
            verify(paymentClient).cancel(PAYMENT_KEY, "승인 금액 불일치");
            verify(paymentStateService).fail(ORDER_ID, ErrorCode.PAYMENT_AMOUNT_MISMATCH, AMOUNT - 1);
            verify(paymentStateService, never()).complete(any(), any());
        }

        @Test
        @DisplayName("승인 후 결과 반영이 실패하면 PG 승인을 취소하고 실패를 확정한다")
        void completeFailsThenCompensate() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willReturn(APPROVED);
            given(paymentStateService.complete(ORDER_ID, APPROVED)).willThrow(new IllegalStateException("반영 실패"));

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(IllegalStateException.class);
            verify(paymentClient).cancel(PAYMENT_KEY, "결제 반영 실패");
            verify(paymentStateService).fail(ORDER_ID, ErrorCode.INTERNAL_SERVER_ERROR, AMOUNT);
        }

        @Test
        @DisplayName("보상 취소마저 실패하면 돈이 묶였는지 모르므로 PAYMENT_CANCEL_FAILED 사유로 결과 모름을 남긴다")
        void compensationFails() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willReturn(new PgApproveResult(PAYMENT_KEY, AMOUNT - 1));
            willThrow(new PaymentCancelDeclinedException("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.")).given(paymentClient).cancel(anyString(), anyString());

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(PaymentCancelDeclinedException.class);
            verify(paymentStateService).markUnknown(ORDER_ID, ErrorCode.PAYMENT_CANCEL_FAILED, AMOUNT - 1);
            verify(paymentStateService, never()).fail(any(), any(), any());
        }

        @Test
        @DisplayName("실패 기록 자체가 실패해도 사용자에게는 원래 PG 예외를 돌려준다")
        void failRecordErrorDoesNotHideCause() {
            // given
            startPayingProceeds();
            given(paymentClient.approve(any())).willThrow(new PaymentDeclinedException("한도 초과"));
            willThrow(new IllegalStateException("DB 오류")).given(paymentStateService)
                    .fail(ORDER_ID, ErrorCode.PAYMENT_DECLINED, null);

            // when // then
            assertThatThrownBy(() -> approve()).isInstanceOf(PaymentDeclinedException.class);
        }
    }
}
