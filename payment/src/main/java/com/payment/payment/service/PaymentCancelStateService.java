package com.payment.payment.service;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentCancel;
import com.payment.payment.domain.PaymentCancelRepository;
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
 * <p>
 * 결과 반영 메서드는 모두 주문 락을 잡은 뒤 도메인 메서드의 상태 검사(CANCELING, REQUESTED)를 조건으로 전이한다.
 * 대사(4-4)와 늦게 도착한 반영이 겹쳐도 한쪽만 반영된다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCancelStateService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;
    private final PaymentCancelReplayer replayer;

    /**
     * 같은 키의 재전송이면 저장된 결과로 답하고, 아니면 PAID → CANCELING으로 주문을 선점하고 취소 시도를 REQUESTED로 기록한다.
     * 선점에 성공한 요청만 PG 취소를 호출할 수 있다.
     */
    @Transactional
    public StartCancelResult startCanceling(StartCancelCommand command) {
        Optional<StartCancelResult> replayed = replayer.replayIfKeyExists(command);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        Order order = findOrderForUpdate(command.orderId());
        if (order.getStatus() != OrderStatus.PAID) {
            return replayer.rejectNotPaid(order, command);
        }

        order.startCanceling();
        PaymentCancel attempt = paymentCancelRepository.save(
                PaymentCancel.request(order.getId(), command.idempotencyKey(), command.reason()));
        Payment payment = findPayment(order.getId());
        return new StartCancelResult.Proceed(new CancelTarget(attempt.getId(), order.getId(), payment.getPaymentKey()));
    }

    /**
     * 같은 키 INSERT가 UNIQUE 제약에 걸려 선점 트랜잭션이 롤백된 뒤 다시 답한다.
     * (키 조회와 주문 락 사이에 같은 키의 시도가 끝까지 진행돼 주문이 다시 PAID가 된 드문 경우)
     */
    @Transactional(readOnly = true)
    public Optional<StartCancelResult> replay(StartCancelCommand command) {
        return replayer.replayIfKeyExists(command);
    }

    /** PG 취소 성공을 반영한다. 취소 시도 COMPLETED, 주문 CANCELED, 결제 CANCELED, 재고 복구를 한 트랜잭션으로 처리한다. */
    @Transactional
    public PaymentResponse completeCancel(CancelTarget target) {
        Order order = findOrderForUpdate(target.orderId());
        PaymentCancel attempt = findAttempt(target.cancelId());
        Payment payment = findPayment(target.orderId());

        attempt.complete();
        order.markCanceled();
        payment.cancel();

        Product product = productRepository.findByIdForUpdate(order.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.increaseStock(order.getQuantity());

        return PaymentResponse.of(order, payment);
    }

    /**
     * 취소가 일어나지 않았음이 확정됐다. (PG 거절, 요청이 PG에 닿지 않음) 취소 시도 FAILED, 주문 CANCELING → PAID.
     * 주문을 PAID로 되돌리는 것은 확정된 결과를 지우는 것이 아니라 진행 중인 취소 시도가 무산된 것이다. 시도는 기록으로 남는다.
     */
    @Transactional
    public void failCancel(CancelTarget target, ErrorCode failureCode) {
        Order order = findOrderForUpdate(target.orderId());
        findAttempt(target.cancelId()).fail(failureCode);
        order.revertToPaid();
    }

    /** PG 취소 응답을 놓쳤다. 취소 시도 UNKNOWN, 주문 CANCELING → CANCEL_UNKNOWN. 재고는 건드리지 않는다. (대사의 몫) */
    @Transactional
    public void markCancelUnknown(CancelTarget target) {
        Order order = findOrderForUpdate(target.orderId());
        findAttempt(target.cancelId()).markUnknown();
        order.markCancelUnknown();
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    private PaymentCancel findAttempt(Long cancelId) {
        return paymentCancelRepository.findById(cancelId)
                .orElseThrow(() -> new IllegalStateException("취소 시도 기록이 없습니다. cancelId=" + cancelId));
    }

    private Payment findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 기록이 없는 주문입니다. orderId=" + orderId));
    }
}
