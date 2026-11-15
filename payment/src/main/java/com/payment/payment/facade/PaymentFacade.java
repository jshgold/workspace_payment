package com.payment.payment.facade;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.PaymentStateService;
import com.payment.payment.service.PaymentTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 결제 승인 오케스트레이션. 이 클래스에는 {@code @Transactional}이 없다. (ARCHITECTURE 5-2: 선 외부 I/O → 후 DB 트랜잭션)
 * <pre>
 * 1. startPaying  (짧은 트랜잭션) 주문 선점 PENDING → PAYING
 * 2. PG 승인 호출  (트랜잭션 밖)   DB 커넥션을 쥐지 않는다
 * 3. complete     (짧은 트랜잭션) Order PAID + Payment 저장 + 재고 차감
 * 4. 3번 실패 시   PG 승인 취소(보상) 후 선점 해제
 * </pre>
 * 실패를 "돈이 나갔는지 아는가"로 나눠 처리한다.
 * <ul>
 *   <li>거절: 돈 안 나감 → PENDING으로 복구</li>
 *   <li>타임아웃·알 수 없는 예외: <b>모름</b> → PAYING 유지 (복구는 결제 조회/대사의 몫)</li>
 *   <li>승인 후 반영 실패: 돈 나감 → PG 취소, 취소가 성공해야만 PENDING으로 복구</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentStateService paymentStateService;
    private final PaymentClient paymentClient;

    public PaymentResponse approve(Long orderId) {
        PaymentTarget target = paymentStateService.startPaying(orderId);

        PgApproveResult approveResult = requestApproval(target);

        if (approveResult.approvedAmount() != target.amount()) {
            log.error("PG 승인 금액 불일치. orderId={}, 서버 금액={}, 승인 금액={}",
                    orderId, target.amount(), approveResult.approvedAmount());
            compensate(orderId, approveResult.pgTransactionId(), "승인 금액 불일치");
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        return complete(orderId, approveResult);
    }

    private PgApproveResult requestApproval(PaymentTarget target) {
        try {
            return paymentClient.approve(new PgApproveRequest(target.orderId(), target.amount()));
        } catch (PaymentDeclinedException e) {
            // 명확한 거절: 돈이 나가지 않았으므로 재시도할 수 있게 선점을 푼다.
            paymentStateService.revertToPending(target.orderId());
            throw e;
        }
        // 타임아웃 등 그 외 예외는 승인 여부를 모른다. 되돌리면 승인된 결제를 놓칠 수 있어 PAYING을 유지한다.
    }

    private PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        try {
            return paymentStateService.complete(orderId, approveResult);
        } catch (DataIntegrityViolationException e) {
            // 이미 같은 주문의 결제가 기록된 경우일 수 있다. 정상 결제를 취소하면 안 되므로 PG 취소를 하지 않고 PAYING을 유지한다.
            log.error("결제 반영 중 무결성 위반. PG 취소 없이 확인 필요. orderId={}, pgTransactionId={}",
                    orderId, approveResult.pgTransactionId(), e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("PG 승인 후 결제 반영 실패. 승인 취소를 시도한다. orderId={}, pgTransactionId={}",
                    orderId, approveResult.pgTransactionId(), e);
            compensate(orderId, approveResult.pgTransactionId(), "결제 반영 실패");
            throw e;
        }
    }

    /** PG 승인을 취소하고, 취소가 성공한 경우에만 선점을 푼다. 취소가 실패하면 PAYING으로 남겨 추적할 수 있게 한다. */
    private void compensate(Long orderId, String pgTransactionId, String reason) {
        try {
            paymentClient.cancel(pgTransactionId, reason);
        } catch (PaymentCancelFailedException e) {
            log.error("보상 취소 실패. 돈이 묶여 있을 수 있어 PAYING을 유지한다. orderId={}, pgTransactionId={}",
                    orderId, pgTransactionId, e);
            throw e;
        }
        paymentStateService.revertToPending(orderId);
    }
}
