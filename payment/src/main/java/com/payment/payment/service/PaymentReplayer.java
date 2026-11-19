package com.payment.payment.service;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.dto.PaymentResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 시작에서 "새 시도가 아닌 요청"에 답한다. 두 {@link PaymentStateService} 구현체가 같이 쓴다.
 * 반드시 호출하는 쪽의 트랜잭션 안에서 쓴다.
 * <ul>
 *   <li>같은 키의 재전송: 저장된 시도의 상태로 답한다. (replay)</li>
 *   <li>다른 키인데 주문이 이미 PENDING이 아님: 상태에 따라 "처리 중" 또는 "결제할 수 없는 주문"으로 거절한다.</li>
 * </ul>
 * 락/상태 선점은 서버 안의 동시 처리를 막고, 멱등키는 재전송에 같은 결과를 돌려준다. 둘은 서로 대체하지 않는다.
 */
@Component
@RequiredArgsConstructor
class PaymentReplayer {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    /** 락 없이 키로 먼저 찾는다. 있으면 재전송이므로 저장된 결과로 답한다. */
    Optional<StartPayingResult> replayIfKeyExists(String idempotencyKey, Long orderId) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(payment -> replay(payment, orderId));
    }

    /**
     * 주문이 PENDING이 아닐 때 호출한다. {@code order}는 락으로 읽은 최신 상태여야 한다.
     * 키 조회와 주문 락 사이에 같은 키의 시도가 커밋됐을 수 있으므로, 잠금 읽기로 한 번 더 확인한다.
     */
    StartPayingResult rejectNotPending(Order order, String idempotencyKey) {
        Optional<Payment> existing = paymentRepository.findByOrderIdForShare(order.getId());
        if (existing.isPresent() && existing.get().getIdempotencyKey().equals(idempotencyKey)) {
            return replay(existing.get(), order.getId());
        }
        if (order.getStatus().isPaymentInProgressOrDone()) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
    }

    private StartPayingResult replay(Payment payment, Long orderId) {
        if (!payment.belongsTo(orderId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (payment.getStatus()) {
            case REQUESTED, UNKNOWN -> throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            case FAILED -> throw new BusinessException(ErrorCode.valueOf(payment.getFailureCode()));
            case APPROVED, CANCELED -> {
                Order order = orderRepository.findById(orderId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
                yield new StartPayingResult.Replayed(PaymentResponse.of(order, payment));
            }
        };
    }
}
