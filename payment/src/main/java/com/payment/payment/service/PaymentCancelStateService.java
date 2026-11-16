package com.payment.payment.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.dto.PaymentResponse;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소 흐름에서 DB를 건드리는 짧은 트랜잭션들. 비관적 락 구현 하나만 둔다.
 * PG 호출은 여기에 넣지 않고 트랜잭션이 없는 {@code PaymentCancelFacade}가 메서드 사이에서 수행한다. (ARCHITECTURE 5-2)
 * <p>
 * 잠그는 순서는 승인({@link PessimisticLockPaymentStateService})과 같은 <b>orders → product</b>다.
 * 취소와 승인이 같은 상품을 동시에 건드려도 서로 반대 순서로 잠그지 않으므로 데드락이 나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCancelStateService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;

    /** PAID → CANCELING으로 주문을 선점한다. 선점에 성공한 요청만 PG 취소를 호출할 수 있다. */
    @Transactional
    public CancelTarget startCanceling(Long orderId) {
        Order order = findOrderForUpdate(orderId);
        order.startCanceling();
        Payment payment = findPayment(orderId);
        return new CancelTarget(order.getId(), payment.getPgTransactionId());
    }

    /**
     * PG 취소 결과를 반영한다. Order CANCELED와 재고 복구를 한 트랜잭션으로 처리한다.
     * 이미 CANCELED인 주문이면(중복 호출) 재고를 또 올리지 않고 기존 결과를 돌려준다.
     */
    @Transactional
    public PaymentResponse completeCancel(Long orderId) {
        Order order = findOrderForUpdate(orderId);
        Payment payment = findPayment(orderId);

        if (order.getStatus() == OrderStatus.CANCELED) {
            return PaymentResponse.of(order, payment);
        }

        order.markCanceled();

        Product product = productRepository.findByIdForUpdate(order.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.increaseStock(order.getQuantity());

        return PaymentResponse.of(order, payment);
    }

    /** CANCELING → PAID. PG가 취소를 명확히 거절해 결제가 그대로 살아 있을 때만 호출한다. */
    @Transactional
    public void revertToPaid(Long orderId) {
        findOrderForUpdate(orderId).revertToPaid();
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    private Payment findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 기록이 없는 주문입니다. orderId=" + orderId));
    }
}
