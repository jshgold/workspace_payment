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
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [비관적 락 구현] phase-02 결론(비관적 락 기본)에 따라 {@code PaymentFacade}에 주입되는 기본 구현체다. ({@code @Primary})
 * 다른 구현체는 테스트에서 직접 주입해 같은 시나리오로 비교한다.
 * <p>
 * 주문과 상품 행을 {@code SELECT ... FOR UPDATE}로 잠그고 도메인 메서드로 상태를 바꾼다.
 * <p>
 * 잠그는 순서는 항상 <b>orders → product</b>다. 다른 경로(결제 취소 등)도 같은 순서를 지켜야 데드락이 나지 않는다.
 * 재고는 PG 호출 전({@code startPaying})에 차감하고, 결제가 실패로 확정되면 {@code fail}에서 복구한다.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class PessimisticLockPaymentStateService implements PaymentStateService {

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

        Order order = findOrderForUpdate(command.orderId());
        if (order.getStatus() != OrderStatus.PENDING) {
            return replayer.rejectNotPending(order, command.idempotencyKey(), command.requestedAmount());
        }

        // 클라이언트가 보낸 금액은 믿지 않는다. 정상 흐름에서는 다를 수 없으므로 시도로 기록하고 주문을 끝낸다.
        if (order.getTotalPrice() != command.requestedAmount()) {
            log.warn("결제 요청 금액 불일치. orderId={}, 서버 금액={}, 요청 금액={}",
                    order.getId(), order.getTotalPrice(), command.requestedAmount());
            return rejectBeforePaying(order, command, ErrorCode.PAYMENT_REQUEST_AMOUNT_MISMATCH);
        }

        Product product = findProductForUpdate(order.getProductId());
        if (!product.canDecreaseStock(order.getQuantity())) {
            return rejectBeforePaying(order, command, ErrorCode.STOCK_NOT_AVAILABLE);
        }

        product.decreaseStock(order.getQuantity());
        order.startPaying();
        paymentRepository.save(Payment.request(
                order.getId(), command.idempotencyKey(), command.paymentKey(), order.getTotalPrice()));
        return new StartPayingResult.Proceed(
                new PaymentTarget(order.getId(), order.getOrderNo(), order.getTotalPrice()));
    }

    @Override
    @Transactional
    public PaymentResponse complete(Long orderId, PgApproveResult approveResult) {
        Order order = findOrderForUpdate(orderId);
        Payment payment = findPayment(orderId);
        // 이미 처리된 주문(중복 반영)은 예외가 아니라 정상 반환으로 걸러낸다. 호출자가 PG 취소를 하지 않도록.
        if (order.getStatus() == OrderStatus.PAID) {
            return PaymentResponse.of(order, payment);
        }

        order.markPaid();
        payment.approve(approveResult.approvedAmount());

        // 처리는 커밋 뒤(AFTER_COMMIT)라서, 이 트랜잭션이 롤백되면 이벤트는 버려진다.
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                orderId, payment.getId(), payment.getPaymentKey(), payment.getAmount()));
        return PaymentResponse.of(order, payment);
    }

    /** 잠그는 순서는 startPaying과 같이 orders → product다. */
    @Override
    @Transactional
    public void fail(Long orderId, ErrorCode failureCode, Long approvedAmount) {
        Order order = findOrderForUpdate(orderId);
        order.markFailed();
        findPayment(orderId).fail(failureCode, approvedAmount);
        findProductForUpdate(order.getProductId()).increaseStock(order.getQuantity());
    }

    @Override
    @Transactional
    public void markUnknown(Long orderId, ErrorCode reason, Long approvedAmount) {
        findOrderForUpdate(orderId).markPaymentUnknown();
        findPayment(orderId).markUnknown(reason, approvedAmount);
    }

    private StartPayingResult rejectBeforePaying(Order order, StartPayingCommand command, ErrorCode reason) {
        order.rejectBeforePaying();
        paymentRepository.save(Payment.rejected(
                order.getId(), command.idempotencyKey(), command.paymentKey(), order.getTotalPrice(), reason));
        return new StartPayingResult.Rejected(reason);
    }

    private Payment findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 시도 기록이 없는 주문입니다. orderId=" + orderId));
    }

    private Product findProductForUpdate(Long productId) {
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
