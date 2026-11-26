package com.payment.payment.facade;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.PaymentStateService;
import com.payment.payment.service.PaymentTarget;
import com.payment.payment.service.StartPayingCommand;
import com.payment.payment.service.StartPayingResult;

import java.time.Duration;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 결제 승인 오케스트레이션. 이 클래스에는 {@code @Transactional}이 없다. (ARCHITECTURE 5-2: 선 외부 I/O → 후 DB 트랜잭션)
 * <pre>
 * 1. startPaying (짧은 트랜잭션) 멱등키 재전송 확인 + 주문 선점 + 재고 차감 + 시도 기록(Payment REQUESTED)
 *                               PG 호출 전 거절(재고 부족, 요청 금액 불일치)이면 실패를 기록하고 여기서 끝난다
 * 2. PG 승인 호출 (트랜잭션 밖)   DB 커넥션을 쥐지 않는다
 * 3. 결과 반영    (짧은 트랜잭션) 아래 셋 중 하나
 * </pre>
 * PG 결과는 "돈이 나갔는지 아는가"로 셋으로 나눈다. 이 분류를 섞지 않는다.
 * <ul>
 *   <li>성공: {@code complete} → PAID / APPROVED</li>
 *   <li>명확한 실패 (거절, 요청이 PG에 닿지 않은 연동 오류): {@code fail} → FAILED / FAILED, 재고 복구</li>
 *   <li>결과 모름 (타임아웃, 5xx, 알 수 없는 예외): {@code markUnknown} → PAYMENT_UNKNOWN / UNKNOWN, 재고 유지 (대사의 몫)</li>
 * </ul>
 * PG 승인 금액이 서버 금액과 다르면 PG 승인을 취소(보상)한다. 취소가 성공해야 FAILED로 확정하고,
 * 취소도 실패하면 돈이 묶였는지 모르므로 결과 모름으로 둔다.
 * <p>
 * 서버 금액 그대로 승인됐는데 반영(DB)만 실패하면 보상 취소하지 않는다. 고객이 동의했고 돈도 나간 정상 결제이므로
 * 결과 모름으로 남기고 대사가 PAID로 확정한다. (intent 8절 "승인 후 DB 반영 실패", 2026-11-26)
 * <p>
 * 요청 1건의 트랜잭션은 2번이다. (트러블슈팅 개선 B, 재설계 전 4번)
 * <p>
 * PG 승인·보상 취소가 결과 모름이면 같은 PG 멱등키로 1번 다시 묻는다({@link PgRetry}). 승인 키는 주문번호로 만들고
 * ({@link PgApproveRequest#idempotencyKey()}), 보상 취소 키는 시도 기록이 없으므로 호출마다 새로 만든다. (phase-04 4-5)
 */
@Slf4j
@Service
public class PaymentFacade {

    private final PaymentStateService paymentStateService;
    private final PaymentClient paymentClient;
    private final Duration pgRetryDelay;

    @Autowired
    public PaymentFacade(PaymentStateService paymentStateService, PaymentClient paymentClient,
                         @Value("${app.pg.retry-delay:200ms}") Duration pgRetryDelay) {
        this.paymentStateService = paymentStateService;
        this.paymentClient = paymentClient;
        this.pgRetryDelay = pgRetryDelay;
    }

    /** 테스트에서 직접 만들 때 쓴다. 재시도 전에 기다리지 않는다. */
    public PaymentFacade(PaymentStateService paymentStateService, PaymentClient paymentClient) {
        this(paymentStateService, paymentClient, Duration.ZERO);
    }

    public PaymentResponse approve(Long orderId, String idempotencyKey, long requestedAmount, String paymentKey) {
        // 형식이 틀린 요청은 시도로 기록할 수 없으므로(필수 값 없음) 트랜잭션 전에 거절한다.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키는 필수입니다.");
        }
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new IllegalArgumentException("결제 키는 필수입니다.");
        }

        // [측정 전용] 구간별 소요 시간 기록. 흐름에는 영향이 없다. (PaymentSegmentTimer)
        PaymentSegmentTimer.start(orderId);
        String result = "FAIL";
        try {
            StartPayingResult started = PaymentSegmentTimer.time("startPaying", () -> paymentStateService.startPaying(
                    new StartPayingCommand(orderId, idempotencyKey, requestedAmount, paymentKey)));

            PaymentResponse response = switch (started) {
                case StartPayingResult.Replayed replayed -> {
                    result = "REPLAY";
                    yield replayed.response();
                }
                case StartPayingResult.Rejected rejected -> throw new BusinessException(rejected.errorCode());
                case StartPayingResult.Proceed proceed -> approveWithPg(proceed.target(), paymentKey);
            };
            if (!"REPLAY".equals(result)) {
                result = "OK";
            }
            return response;
        } finally {
            PaymentSegmentTimer.finish(result);
        }
    }

    private PaymentResponse approveWithPg(PaymentTarget target, String paymentKey) {
        PgApproveResult approveResult = requestApproval(target, paymentKey);

        if (approveResult.approvedAmount() != target.amount()) {
            log.error("PG 승인 금액 불일치. orderId={}, 서버 금액={}, 승인 금액={}",
                    target.orderId(), target.amount(), approveResult.approvedAmount());
            compensate(target.orderId(), approveResult, ErrorCode.PAYMENT_AMOUNT_MISMATCH, "승인 금액 불일치");
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        return complete(target.orderId(), approveResult);
    }

    private PgApproveResult requestApproval(PaymentTarget target, String paymentKey) {
        try {
            PgApproveRequest request = new PgApproveRequest(target.orderNo(), paymentKey, target.amount());
            return PaymentSegmentTimer.time("pgApprove", () -> PgRetry.onceOnUnknown(
                    pgRetryDelay, "승인", target.orderId(), () -> paymentClient.approve(request)));
        } catch (PaymentDeclinedException e) {
            recordFailure(target.orderId(), ErrorCode.PAYMENT_DECLINED, null);
            throw e;
        } catch (PaymentGatewayException e) {
            // 요청이 PG에 닿지 않았음이 확실한 연동 오류(연결 실패, 인증 오류 등). 돈이 나가지 않았다.
            recordFailure(target.orderId(), ErrorCode.PAYMENT_GATEWAY_ERROR, null);
            throw e;
        } catch (RuntimeException e) {
            // 타임아웃·5xx·알 수 없는 예외는 승인 여부를 모른다. 실패로 확정하면 승인된 결제를 놓칠 수 있다.
            recordUnknown(target.orderId(), null, null, e);
            throw e;
        }
    }

    private PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        try {
            return PaymentSegmentTimer.time("complete", () -> paymentStateService.complete(orderId, approveResult));
        } catch (RuntimeException e) {
            // 돈은 서버 금액 그대로 나갔고 실패한 것은 우리 기록뿐이다. 되돌리지 않고 대사가 PAID로 확정한다.
            log.error("PG 승인 후 결제 반영 실패. 보상 취소하지 않고 결과 모름으로 남겨 대사에 맡긴다. orderId={}, pgTransactionId={}",
                    orderId, approveResult.pgTransactionId(), e);
            recordUnknown(orderId, null, approveResult.approvedAmount(), e);
            throw e;
        }
    }

    /**
     * PG 승인을 취소하고, 취소가 성공한 경우에만 실패로 확정한다(재고 복구 포함).
     * 취소가 실패하면 돈이 묶였는지 모르므로 결과 모름(PAYMENT_UNKNOWN + PAYMENT_CANCEL_FAILED)으로 남겨 대사 대상으로 만든다.
     */
    private void compensate(Long orderId, PgApproveResult approveResult, ErrorCode failureCode, String reason) {
        try {
            // 보상 취소는 시도 기록이 없으므로 호출마다 키를 새로 만든다. 이 호출 안의 재시도만 같은 키를 쓴다.
            String pgIdempotencyKey = UUID.randomUUID().toString();
            PaymentSegmentTimer.time("pgCancel", () -> PgRetry.onceOnUnknown(pgRetryDelay, "보상 취소", orderId,
                    () -> paymentClient.cancel(approveResult.pgTransactionId(), reason, pgIdempotencyKey)));
        } catch (RuntimeException e) {
            log.error("보상 취소 실패. 돈이 묶여 있을 수 있어 결과 모름으로 남긴다. orderId={}, pgTransactionId={}",
                    orderId, approveResult.pgTransactionId(), e);
            recordUnknown(orderId, ErrorCode.PAYMENT_CANCEL_FAILED, approveResult.approvedAmount(), e);
            throw e;
        }
        recordFailure(orderId, failureCode, approveResult.approvedAmount());
    }

    /**
     * 실패 기록 자체가 실패해도 호출자가 던질 원래 예외를 가리지 않는다. (로그로 남긴다)
     * 이때 주문은 PAYING으로 남고, 오래된 PAYING은 대사가 확정한다. (4-4)
     */
    private void recordFailure(Long orderId, ErrorCode failureCode, Long approvedAmount) {
        try {
            PaymentSegmentTimer.time("fail", () -> paymentStateService.fail(orderId, failureCode, approvedAmount));
        } catch (RuntimeException e) {
            log.error("결제 실패 기록에 실패했다. PAYING으로 남아 대사가 필요하다. orderId={}, failureCode={}",
                    orderId, failureCode, e);
        }
    }

    private void recordUnknown(Long orderId, ErrorCode reason, Long approvedAmount, RuntimeException cause) {
        try {
            PaymentSegmentTimer.time("unknown", () -> paymentStateService.markUnknown(orderId, reason, approvedAmount));
        } catch (RuntimeException e) {
            log.error("결제 결과 모름 기록에 실패했다. PAYING으로 남아 대사가 필요하다. orderId={}, cause={}",
                    orderId, cause.getClass().getSimpleName(), e);
        }
    }
}
