package com.payment.payment.facade;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PgCancelResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.CancelTarget;
import com.payment.payment.service.PaymentCancelStateService;
import com.payment.payment.service.StartCancelCommand;
import com.payment.payment.service.StartCancelResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 결제 취소 오케스트레이션. 이 클래스에는 {@code @Transactional}이 없다. (ARCHITECTURE 5-2)
 * <pre>
 * 1. startCanceling (짧은 트랜잭션) 멱등키 재전송 확인 + 주문 선점 PAID → CANCELING + 시도 기록(REQUESTED)
 * 2. PG 취소 호출    (트랜잭션 밖)   DB 커넥션을 쥐지 않는다
 * 3. 결과 반영       (짧은 트랜잭션) 아래 셋 중 하나
 * </pre>
 * PG 결과는 "돈이 돌아갔는지 아는가"로 셋으로 나눈다.
 * <ul>
 *   <li>성공 (이미 취소됨 포함): {@code completeCancel} → COMPLETED / CANCELED, 재고 복구</li>
 *   <li>명확한 실패 (거절, 요청이 PG에 닿지 않음): {@code failCancel} → FAILED / PAID. 결제가 살아 있으므로 새 키로 다시 취소할 수 있다</li>
 *   <li>결과 모름 (타임아웃, 5xx): {@code markCancelUnknown} → UNKNOWN / CANCEL_UNKNOWN. PAID로 되돌리면 환불된 결제를 결제 완료로 오인한다</li>
 * </ul>
 * 결과 반영 자체가 실패하면(PG 성공 후 DB 실패 등) CANCELING / REQUESTED로 남고, 대사(4-4)가 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelFacade {

    private final PaymentCancelStateService cancelStateService;
    private final PaymentClient paymentClient;

    public PaymentResponse cancel(Long orderId, String idempotencyKey, String reason) {
        // 형식이 틀린 요청은 시도로 기록할 수 없으므로 트랜잭션 전에 거절한다.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키는 필수입니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("취소 사유는 필수입니다.");
        }

        StartCancelCommand command = new StartCancelCommand(orderId, idempotencyKey, reason);
        return switch (start(command)) {
            case StartCancelResult.Replayed replayed -> replayed.response();
            case StartCancelResult.Proceed proceed -> cancelWithPg(proceed.target(), reason);
        };
    }

    private StartCancelResult start(StartCancelCommand command) {
        try {
            return cancelStateService.startCanceling(command);
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 시도가 이미 있다. (키 조회 뒤 같은 키 요청이 끝까지 진행돼 주문이 다시 PAID가 된 경우) 그 결과로 답한다.
            return cancelStateService.replay(command).orElseThrow(() -> e);
        }
    }

    private PaymentResponse cancelWithPg(CancelTarget target, String reason) {
        PgCancelResult result = requestCancel(target, reason);
        if (result.alreadyCanceled()) {
            log.warn("첫 취소 시도에 PG가 이미 취소된 결제라고 답했다. 우리가 모르는 경로(상점관리자 등)로 취소된 것으로 보인다. orderId={}, paymentKey={}",
                    target.orderId(), target.paymentKey());
        }
        return complete(target);
    }

    private PgCancelResult requestCancel(CancelTarget target, String reason) {
        try {
            return paymentClient.cancel(target.paymentKey(), reason);
        } catch (PaymentCancelDeclinedException e) {
            log.warn("PG가 취소를 거절했다. PAID로 되돌린다. orderId={}, paymentKey={}", target.orderId(), target.paymentKey(), e);
            recordFailure(target, ErrorCode.PAYMENT_CANCEL_DECLINED);
            throw e;
        } catch (PaymentGatewayException e) {
            log.warn("취소 요청이 PG에 닿지 않았다. PAID로 되돌린다. orderId={}, paymentKey={}", target.orderId(), target.paymentKey(), e);
            recordFailure(target, ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
        } catch (RuntimeException e) {
            // 타임아웃·5xx·알 수 없는 예외는 취소 여부를 모른다. PAID로 되돌리면 환불된 결제를 결제 완료로 오인할 수 있다.
            log.warn("PG 취소 결과를 알 수 없다. CANCEL_UNKNOWN으로 남긴다. orderId={}, paymentKey={}", target.orderId(), target.paymentKey(), e);
            recordUnknown(target);
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_UNKNOWN);
        }
    }

    private PaymentResponse complete(CancelTarget target) {
        try {
            return cancelStateService.completeCancel(target);
        } catch (RuntimeException e) {
            log.error("PG 취소는 성공했으나 DB 반영에 실패했다. CANCELING으로 남아 대사가 필요하다. orderId={}, paymentKey={}",
                    target.orderId(), target.paymentKey(), e);
            throw e;
        }
    }

    /** 실패 기록 자체가 실패해도 호출자가 던질 원래 예외를 가리지 않는다. 이때 주문은 CANCELING으로 남고 대사가 확정한다. */
    private void recordFailure(CancelTarget target, ErrorCode failureCode) {
        try {
            cancelStateService.failCancel(target, failureCode);
        } catch (RuntimeException e) {
            log.error("취소 실패 기록에 실패했다. CANCELING으로 남아 대사가 필요하다. orderId={}, failureCode={}",
                    target.orderId(), failureCode, e);
        }
    }

    private void recordUnknown(CancelTarget target) {
        try {
            cancelStateService.markCancelUnknown(target);
        } catch (RuntimeException e) {
            log.error("취소 결과 모름 기록에 실패했다. CANCELING으로 남아 대사가 필요하다. orderId={}", target.orderId(), e);
        }
    }
}
