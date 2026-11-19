package com.payment.payment.service;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [원자적 쿼리 구현] 조건부 UPDATE 한 문장이 관문이 된다. 행을 읽어서 판단하지 않고 DB가 확인과 변경을 한 번에 한다.
 * <ul>
 *   <li>선점: {@code UPDATE orders SET status='PAYING' WHERE id=? AND status='PENDING'} — 0행이면 선점 실패.
 *       이어서 조건부 UPDATE로 재고를 차감하고, 0행이면 선점까지 롤백한다. (PG 호출 전에 재고 판정)</li>
 *   <li>반영: 같은 방식으로 PAYING → PAID를 먼저 통과한 요청만 Payment INSERT로 진행한다.
 *       중복 메시지는 0행이라 INSERT까지 가지 않으므로 UNIQUE 위반 예외로 걸러지지 않는다.</li>
 *   <li>되돌리기: PAYING → PENDING과 재고 복구를 한 트랜잭션에서 한다.</li>
 *   <li>재고 UPDATE는 트랜잭션 맨 끝에 둔다. UPDATE가 잡은 재고 행 락은 커밋까지 유지되므로 보유 시간을 줄이기 위해서다.</li>
 * </ul>
 * 비관적 락 구현({@link PessimisticLockPaymentStateService})과 달리 상태 규칙이 SQL에 한 번 더 들어간다.
 */
@Service
@RequiredArgsConstructor
public class AtomicUpdatePaymentStateService implements PaymentStateService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public PaymentTarget startPaying(Long orderId) {
        int updated = orderRepository.transitionStatus(orderId, OrderStatus.PENDING, OrderStatus.PAYING);
        if (updated == 0) {
            throw rejection(orderId, "결제를 시작할 수 없는 주문 상태입니다.");
        }
        Order order = findOrder(orderId);
        // 아래 벌크 UPDATE가 영속성 컨텍스트를 비우므로, 필요한 값은 그 전에 꺼내 둔다.
        PaymentTarget target = new PaymentTarget(order.getId(), order.getOrderNo(), order.getTotalPrice());

        // 주문 선점과 재고 차감을 한 트랜잭션에서 한다. 재고가 모자라면 선점까지 롤백되어 PG를 부르지 않는다.
        // (트러블슈팅 개선 A: docs/troubleshooting/payment-stock-deduction-timing.md)
        int decreased = productRepository.decreaseStockIfAvailable(order.getProductId(), order.getQuantity());
        if (decreased == 0) {
            throw new BusinessException(ErrorCode.STOCK_NOT_AVAILABLE);
        }
        return target;
    }

    @Override
    @Transactional
    public PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        int updated = orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.PAID);
        if (updated == 0) {
            return alreadyCompletedOrReject(orderId);
        }

        // 재고는 startPaying에서 이미 차감했다. 여기서는 상품 행을 건드리지 않는다.
        Order order = findOrder(orderId);
        Payment payment = paymentRepository.save(
                Payment.create(orderId, approveResult.pgTransactionId(), approveResult.approvedAmount()));

        // 처리는 커밋 뒤(AFTER_COMMIT)라서, 이 트랜잭션이 롤백되면 이벤트는 버려진다.
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                orderId, payment.getId(), payment.getPgTransactionId(), payment.getAmount()));

        return PaymentResponse.of(order, payment);
    }

    /** 선점을 풀고 startPaying에서 차감한 재고를 되돌린다. 잠그는 순서는 startPaying과 같이 orders → product다. */
    @Override
    @Transactional
    public void revertToPending(Long orderId) {
        int updated = orderRepository.transitionStatus(orderId, OrderStatus.PAYING, OrderStatus.PENDING);
        if (updated == 0) {
            throw rejection(orderId, "결제 대기로 되돌릴 수 없는 주문 상태입니다.");
        }
        Order order = findOrder(orderId);
        int restored = productRepository.increaseStock(order.getProductId(), order.getQuantity());
        if (restored == 0) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    /** 이미 PAID면 중복 메시지이므로 예외 없이 기존 결과를 돌려주고, 그 외에는 상태 위반으로 거절한다. */
    private PaymentResponse alreadyCompletedOrReject(Long orderId) {
        Order order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("결제 완료 처리할 수 없는 주문 상태입니다. 현재 상태: " + order.getStatus());
        }
        Payment existing = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("PAID 주문에 결제 기록이 없습니다. orderId=" + orderId));
        return PaymentResponse.of(order, existing);
    }

    /** 0행의 원인을 실패 경로에서만 구분한다. (없는 주문 vs 상태가 맞지 않음) */
    private RuntimeException rejection(Long orderId, String message) {
        Order order = findOrder(orderId);
        return new IllegalStateException(message + " 현재 상태: " + order.getStatus());
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
