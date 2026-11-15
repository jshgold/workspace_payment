package com.payment.payment.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [비관적 락 구현] 주문과 상품 행을 {@code SELECT ... FOR UPDATE}로 잠그고 도메인 메서드로 상태를 바꾼다.
 * <p>
 * 잠그는 순서는 항상 <b>orders → product</b>다. 다른 경로(결제 취소 등)도 같은 순서를 지켜야 데드락이 나지 않는다.
 * 재고 행 락은 트랜잭션 맨 끝에서 잡아 락을 쥐는 시간을 줄인다.
 */
@Service
@RequiredArgsConstructor
public class PessimisticLockPaymentStateService implements PaymentStateService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;

    @Override
    @Transactional
    public PaymentTarget startPaying(Long orderId) {
        Order order = findOrderForUpdate(orderId);
        order.startPaying();
        return new PaymentTarget(order.getId(), order.getTotalPrice());
    }

    @Override
    @Transactional
    public PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        Order order = findOrderForUpdate(orderId);

        // 이미 처리된 주문(중복 메시지)은 예외가 아니라 정상 반환으로 걸러낸다. 호출자가 PG 취소를 하지 않도록.
        if (order.getStatus() == OrderStatus.PAID) {
            Payment existing = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("PAID 주문에 결제 기록이 없습니다. orderId=" + orderId));
            return PaymentResponse.of(order, existing);
        }

        order.markPaid();
        Payment payment = paymentRepository.save(
                Payment.create(orderId, approveResult.pgTransactionId(), approveResult.approvedAmount()));

        Product product = productRepository.findByIdForUpdate(order.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(order.getQuantity());

        return PaymentResponse.of(order, payment);
    }

    @Override
    @Transactional
    public void revertToPending(Long orderId) {
        findOrderForUpdate(orderId).revertToPending();
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
