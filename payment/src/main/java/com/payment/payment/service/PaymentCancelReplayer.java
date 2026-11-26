package com.payment.payment.service;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentCancel;
import com.payment.payment.domain.PaymentCancelRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.dto.PaymentResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 취소 시작에서 "새 시도가 아닌 요청"에 답한다. 승인의 {@link PaymentReplayer}와 같은 역할이다.
 * 반드시 호출하는 쪽의 트랜잭션 안에서 쓴다.
 * <ul>
 *   <li>같은 키의 재전송: 저장된 시도의 상태로 답한다. (replay) 내용(주문, 사유)이 다르면 거절한다.</li>
 *   <li>다른 키인데 주문이 PAID가 아님: 주문 상태로 답한다. 시도가 아니므로 기록하지 않는다.</li>
 * </ul>
 * 상태 선점은 PG 중복 호출을 막고, 멱등키는 재시도와 새 요청을 구분해 replay와 이상 감지를 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PaymentCancelReplayer {

    private final PaymentCancelRepository paymentCancelRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    /** 락 없이 키로 먼저 찾는다. 있으면 재전송이므로 저장된 결과로 답한다. */
    Optional<StartCancelResult> replayIfKeyExists(StartCancelCommand command) {
        return paymentCancelRepository.findByIdempotencyKey(command.idempotencyKey())
                .map(attempt -> replay(attempt, command));
    }

    /**
     * 주문이 PAID가 아닐 때 호출한다. {@code order}는 락으로 읽은 최신 상태여야 한다.
     * 키 조회와 주문 락 사이에 같은 키의 시도가 커밋됐을 수 있으므로 잠금 읽기로 한 번 더 확인한다.
     */
    StartCancelResult rejectNotPaid(Order order, StartCancelCommand command) {
        Optional<PaymentCancel> existing = paymentCancelRepository.findByIdempotencyKeyForShare(command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        throw new BusinessException(switch (order.getStatus()) {
            case CANCELING, CANCEL_UNKNOWN, CANCEL_REVIEW -> ErrorCode.PAYMENT_IN_PROGRESS;
            case CANCELED -> ErrorCode.ORDER_ALREADY_CANCELED;
            default -> ErrorCode.ORDER_NOT_CANCELABLE;
        });
    }

    private StartCancelResult replay(PaymentCancel attempt, StartCancelCommand command) {
        if (!attempt.isSameRequest(command.orderId(), command.reason())) {
            // 진짜 재전송은 본문이 그대로다. 같은 키로 내용이 바뀌었다면 클라이언트 버그나 조작이다.
            log.warn("같은 멱등키로 다른 내용의 취소 요청이 왔다. key={}, 저장된 orderId={}, 요청 orderId={}",
                    command.idempotencyKey(), attempt.getOrderId(), command.orderId());
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (attempt.getStatus()) {
            case REQUESTED, UNKNOWN -> throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            case FAILED -> throw new BusinessException(ErrorCode.valueOf(attempt.getFailureCode()));
            case COMPLETED -> {
                Order order = orderRepository.findById(attempt.getOrderId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
                Payment payment = paymentRepository.findByOrderId(attempt.getOrderId())
                        .orElseThrow(() -> new IllegalStateException("결제 기록이 없는 주문입니다. orderId=" + attempt.getOrderId()));
                yield new StartCancelResult.Replayed(PaymentResponse.of(order, payment));
            }
        };
    }
}
