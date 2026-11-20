package com.payment.payment.service;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentCompletedEvent;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [원자적 쿼리 구현] 조건부 UPDATE 한 문장이 관문이 된다. 행을 읽어서 판단하지 않고 DB가 확인과 변경을 한 번에 한다.
 * <ul>
 *   <li>선점: {@code UPDATE orders SET status='PAYING' WHERE id=? AND status='PENDING'} — 0행이면 선점 실패.
 *       이어서 조건부 UPDATE로 재고를 차감하고, 0행이면 같은 트랜잭션에서 PAYING → FAILED로 바꾸고 실패를 기록한다.
 *       (중간 상태 PAYING은 커밋되지 않으므로 밖에서 보이는 전이는 PENDING → FAILED다)</li>
 *   <li>반영·실패·결과 모름: 모두 {@code WHERE status='PAYING'} 조건부 전이를 먼저 통과한 요청만 Payment를 바꾼다.</li>
 *   <li>재고 UPDATE는 트랜잭션 맨 끝에 둔다. UPDATE가 잡은 재고 행 락은 커밋까지 유지되므로 보유 시간을 줄이기 위해서다.</li>
 * </ul>
 * 비관적 락 구현({@link PessimisticLockPaymentStateService})과 달리 상태 규칙이 SQL에 한 번 더 들어간다.
 * 0행일 때 원인을 가리는 실패 경로에서만 주문을 잠금 읽기로 다시 읽는다. (트랜잭션 스냅샷은 낡았을 수 있다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AtomicUpdatePaymentStateService implements PaymentStateService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentReplayer replayer;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public StartPayingResult startPaying(StartPayingCommand command) {
        Optional<StartPayingResult> replayed = replayer.replayIfKeyExists(command.idempotencyKey(), command.orderId(), command.requestedAmount());
        if (replayed.isPresent()) {
            return replayed.get();
        }

        // 아래 벌크 UPDATE가 영속성 컨텍스트를 비우므로, 바뀌지 않는 값은 먼저 꺼내 둔다.
        Order order = findOrder(command.orderId());
        Long orderId = order.getId();
        Long productId = order.getProductId();
        int quantity = order.getQuantity();
        long amount = order.getTotalPrice();
        PaymentTarget target = new PaymentTarget(orderId, order.getOrderNo(), amount);

        if (amount != command.requestedAmount()) {
            if (orderRepository.transitionStatus(orderId, OrderStatus.PENDING, OrderStatus.FAILED) == 0) {
                return replayer.rejectNotPending(findOrderForUpdate(orderId), command.idempotencyKey(), command.requestedAmount());
            }
            log.warn("결제 요청 금액 불일치. orderId={}, 서버 금액={}, 요청 금액={}", orderId, amount, command.requestedAmount());
            return recordRejected(orderId, command, amount, ErrorCode.PAYMENT_REQUEST_AMOUNT_MISMATCH);
        }

        if (orderRepository.transitionStatus(orderId, OrderStatus.PENDING, OrderStatus.PAYING) == 0) {
            return replayer.rejectNotPending(findOrderForUpdate(orderId), command.idempotencyKey(), command.requestedAmount());
        }

        // 주문 선점과 재고 차감을 한 트랜잭션에서 한다. 재고가 모자라면 PG를 부르지 않고 실패로 기록한다.
        // (트러블슈팅 개선 A: docs/troubleshooting/payment-stock-deduction-timing.md)
        if (productRepository.decreaseStockIfAvailable(productId, quantity) == 0) {
            orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.FAILED);
            return recordRejected(orderId, command, amount, ErrorCode.STOCK_NOT_AVAILABLE);
        }

        paymentRepository.save(Payment.request(orderId, command.idempotencyKey(), command.paymentKey(), amount));
        return new StartPayingResult.Proceed(target);
    }

    @Override
    @Transactional
    public PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        int updated = orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.PAID);
        if (updated == 0) {
            return alreadyCompletedOrReject(orderId);
        }

        // 재고는 startPaying에서 이미 차감했다. 여기서는 상품 행을 건드리지 않는다.
        Payment payment = findPayment(orderId);
        payment.approve(approveResult.approvedAmount());

        // 처리는 커밋 뒤(AFTER_COMMIT)라서, 이 트랜잭션이 롤백되면 이벤트는 버려진다.
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                orderId, payment.getId(), payment.getPaymentKey(), payment.getAmount()));
        return PaymentResponse.of(findOrder(orderId), payment);
    }

    /** 잠그는 순서는 startPaying과 같이 orders → product다. */
    @Override
    @Transactional
    public void fail(Long orderId, ErrorCode failureCode, Long approvedAmount) {
        if (orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.FAILED) == 0) {
            throw rejection(orderId, "결제 실패 처리할 수 없는 주문 상태입니다.");
        }
        findPayment(orderId).fail(failureCode, approvedAmount);

        Order order = findOrder(orderId);
        if (productRepository.increaseStock(order.getProductId(), order.getQuantity()) == 0) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void markUnknown(Long orderId, ErrorCode reason, Long approvedAmount) {
        if (orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.PAYMENT_UNKNOWN) == 0) {
            throw rejection(orderId, "결제 결과 모름으로 바꿀 수 없는 주문 상태입니다.");
        }
        findPayment(orderId).markUnknown(reason, approvedAmount);
    }

    private StartPayingResult recordRejected(Long orderId, StartPayingCommand command, long amount, ErrorCode reason) {
        paymentRepository.save(Payment.rejected(orderId, command.idempotencyKey(), command.paymentKey(), amount, reason));
        return new StartPayingResult.Rejected(reason);
    }

    /** 이미 PAID면 중복 반영이므로 예외 없이 기존 결과를 돌려주고, 그 외에는 상태 위반으로 거절한다. */
    private PaymentResponse alreadyCompletedOrReject(Long orderId) {
        Order order = findOrderForUpdate(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료 처리할 수 없는 주문 상태입니다. 현재 상태: " + order.getStatus());
        }
        return PaymentResponse.of(order, findPayment(orderId));
    }

    /** 0행의 원인을 실패 경로에서만 구분한다. (없는 주문 vs 상태가 맞지 않음) */
    private RuntimeException rejection(Long orderId, String message) {
        Order order = findOrderForUpdate(orderId);
        return new IllegalStateException(message + " 현재 상태: " + order.getStatus());
    }

    private Payment findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 시도 기록이 없는 주문입니다. orderId=" + orderId));
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
