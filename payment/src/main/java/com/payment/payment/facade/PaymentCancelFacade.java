package com.payment.payment.facade;

import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentClient;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.CancelTarget;
import com.payment.payment.service.PaymentCancelStateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 취소 오케스트레이션. 이 클래스에는 {@code @Transactional}이 없다. (ARCHITECTURE 5-2)
 * <pre>
 * 1. startCanceling (짧은 트랜잭션) 주문 선점 PAID → CANCELING
 * 2. PG 취소 호출    (트랜잭션 밖)   DB 커넥션을 쥐지 않는다
 * 3. completeCancel (짧은 트랜잭션) Order CANCELED + 재고 복구
 * </pre>
 * 실패를 "결제가 아직 살아 있는지 아는가"로 나눠 처리한다.
 * <ul>
 *   <li>PG가 취소를 명확히 거절: 결제가 그대로 살아 있음 → PAID로 복구해 재시도할 수 있게 한다</li>
 *   <li>타임아웃·알 수 없는 예외: <b>모름</b> → CANCELING 유지 (복구는 결제 조회/대사의 몫)</li>
 *   <li>PG 취소 성공 후 DB 반영 실패: 돈은 이미 환불됨 → 되돌릴 수 없으므로 CANCELING 유지, 로그로 남긴다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelFacade {

    private final PaymentCancelStateService cancelStateService;
    private final PaymentClient paymentClient;

    public PaymentResponse cancel(Long orderId, String reason) {
        CancelTarget target = cancelStateService.startCanceling(orderId);

        requestCancel(target, reason);

        return complete(target);
    }

    private void requestCancel(CancelTarget target, String reason) {
        try {
            paymentClient.cancel(target.pgTransactionId(), reason);
        } catch (PaymentCancelFailedException e) {
            // 명확한 취소 실패: 결제는 그대로 살아 있으므로 PAID로 되돌려 다시 취소할 수 있게 한다.
            log.warn("PG 취소 실패. PAID로 복구한다. orderId={}, pgTransactionId={}",
                    target.orderId(), target.pgTransactionId(), e);
            cancelStateService.revertToPaid(target.orderId());
            throw e;
        }
        // 타임아웃 등 그 외 예외는 취소 여부를 모른다. 되돌리면 환불된 결제를 PAID로 오인할 수 있어 CANCELING을 유지한다.
    }

    private PaymentResponse complete(CancelTarget target) {
        try {
            return cancelStateService.completeCancel(target.orderId());
        } catch (RuntimeException e) {
            log.error("PG 취소는 성공했으나 DB 반영에 실패했다. CANCELING으로 남으며 확인 필요. orderId={}, pgTransactionId={}",
                    target.orderId(), target.pgTransactionId(), e);
            throw e;
        }
    }
}
