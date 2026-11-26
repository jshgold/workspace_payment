package com.payment.payment.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentCancel;
import com.payment.payment.domain.PaymentCancelRepository;
import com.payment.payment.domain.PaymentCancelStatus;
import com.payment.payment.domain.PaymentCompletedEvent;
import com.payment.payment.domain.PaymentRepository;
import com.payment.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사에서 DB를 건드리는 짧은 트랜잭션들. PG 조회는 여기에 넣지 않고 {@code PaymentReconciliationFacade}가 메서드 사이에서 한다.
 * <p>
 * <b>비관적 락으로 통일한다.</b> (intent 8절 "대사·만료의 상태 전이 방식") 대상 id는 락 없이 고르고,
 * 확정할 때 주문마다 {@code FOR UPDATE} → 현재 상태 재확인 → 도메인 메서드로 전이한다.
 * 고른 뒤 다른 경로(다른 대사 회차 등)가 먼저 확정했으면 상태가 달라져 있으므로 건너뛴다({@code false}). 그래서 한 번만 반영된다.
 * <p>
 * 잠그는 순서는 승인·취소와 같은 <b>orders → product</b>다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationStateService {

    private static final List<OrderStatus> PAYMENT_TARGET_STATUSES = List.of(OrderStatus.PAYING, OrderStatus.PAYMENT_UNKNOWN);
    private static final List<OrderStatus> CANCEL_TARGET_STATUSES = List.of(OrderStatus.CANCELING, OrderStatus.CANCEL_UNKNOWN);
    private static final List<PaymentCancelStatus> OPEN_CANCEL_STATUSES = List.of(PaymentCancelStatus.REQUESTED, PaymentCancelStatus.UNKNOWN);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ---- 대상 고르기 (락 없음) ----

    @Transactional(readOnly = true)
    public ReconcileBatch<PaymentReconcileTarget> findPaymentTargets(LocalDateTime before, long afterId, int size) {
        List<Long> ids = orderRepository.findIdsForBatch(PAYMENT_TARGET_STATUSES, before, afterId, Limit.of(size));
        Map<Long, Payment> payments = paymentRepository.findAllByOrderIdIn(ids).stream()
                .collect(Collectors.toMap(Payment::getOrderId, Function.identity()));

        List<PaymentReconcileTarget> targets = new ArrayList<>();
        for (Long id : ids) {
            Payment payment = payments.get(id);
            if (payment == null) {
                // 결제 시작은 주문 선점과 시도 기록을 한 트랜잭션으로 하므로 생길 수 없다.
                log.error("[승인 대사] 결제 시도 기록이 없는 진행 중 주문이다. 건너뛴다. orderId={}", id);
                continue;
            }
            targets.add(new PaymentReconcileTarget(id, payment.getPaymentKey(), payment.getAmount(),
                    payment.isCompensationFailed(), payment.getApprovedAmount()));
        }
        return new ReconcileBatch<>(targets, ids.size(), ids.isEmpty() ? null : ids.getLast());
    }

    @Transactional(readOnly = true)
    public ReconcileBatch<CancelReconcileTarget> findCancelTargets(LocalDateTime before, long afterId, int size) {
        List<Long> ids = orderRepository.findIdsForBatch(CANCEL_TARGET_STATUSES, before, afterId, Limit.of(size));
        Map<Long, Payment> payments = paymentRepository.findAllByOrderIdIn(ids).stream()
                .collect(Collectors.toMap(Payment::getOrderId, Function.identity()));
        Map<Long, PaymentCancel> attempts = paymentCancelRepository.findAllByOrderIdInAndStatusIn(ids, OPEN_CANCEL_STATUSES).stream()
                .collect(Collectors.toMap(PaymentCancel::getOrderId, Function.identity()));

        List<CancelReconcileTarget> targets = new ArrayList<>();
        for (Long id : ids) {
            Payment payment = payments.get(id);
            PaymentCancel attempt = attempts.get(id);
            if (payment == null || attempt == null) {
                // 취소 선점과 취소 시도 기록은 한 트랜잭션이라 생길 수 없다. 고른 뒤 다른 회차가 확정했으면 시도가 없을 수 있다.
                log.warn("[취소 대사] 결제 또는 미확정 취소 시도가 없다. 건너뛴다. orderId={}, payment={}, attempt={}",
                        id, payment != null, attempt != null);
                continue;
            }
            targets.add(new CancelReconcileTarget(id, attempt.getId(), payment.getPaymentKey()));
        }
        return new ReconcileBatch<>(targets, ids.size(), ids.isEmpty() ? null : ids.getLast());
    }

    // ---- 승인 대사 확정 ----

    /** PG가 서버 금액 그대로 승인했다. PAID / APPROVED. 재고는 결제 시작에서 이미 차감했으므로 건드리지 않는다. */
    @Transactional
    public boolean confirmPaid(Long orderId, long approvedAmount) {
        Order order = findOrderForUpdate(orderId);
        Payment payment = findPayment(orderId);
        if (!order.isAwaitingPaymentReconciliation() || !payment.isAwaitingReconciliation()) {
            return skipped("승인 대사", order, payment.getStatus());
        }
        order.confirmPaidByReconciliation();
        payment.confirmApprovedByReconciliation(approvedAmount);
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                orderId, payment.getId(), payment.getPaymentKey(), payment.getAmount()));
        return true;
    }

    /** 돈이 나가지 않았거나 돌려줬다. FAILED / FAILED + 재고 복구. */
    @Transactional
    public boolean confirmFailed(Long orderId, ErrorCode failureCode, Long approvedAmount) {
        Order order = findOrderForUpdate(orderId);
        Payment payment = findPayment(orderId);
        if (!order.isAwaitingPaymentReconciliation() || !payment.isAwaitingReconciliation()) {
            return skipped("승인 대사", order, payment.getStatus());
        }
        order.confirmFailedByReconciliation();
        payment.confirmFailedByReconciliation(failureCode, approvedAmount);
        productRepository.findByIdForUpdate(order.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                .increaseStock(order.getQuantity());
        return true;
    }

    /** 금액이 다른 승인을 다시 취소하려다 실패했다. PAYMENT_UNKNOWN / UNKNOWN + PAYMENT_CANCEL_FAILED로 두고 다음 회차에 다시 본다. */
    @Transactional
    public boolean keepUnknownAfterCancelFailure(Long orderId, long approvedAmount) {
        Order order = findOrderForUpdate(orderId);
        Payment payment = findPayment(orderId);
        if (!order.isAwaitingPaymentReconciliation() || !payment.isAwaitingReconciliation()) {
            return skipped("승인 대사", order, payment.getStatus());
        }
        order.keepPaymentUnknownByReconciliation();
        payment.keepUnknownAfterCancelFailure(approvedAmount);
        return true;
    }

    // ---- 취소 대사 확정 ----

    /** PG에서 취소됐다. 취소 시도 COMPLETED, 주문 CANCELED, 결제 CANCELED, 재고 복구. */
    @Transactional
    public boolean confirmCanceled(CancelReconcileTarget target) {
        Order order = findOrderForUpdate(target.orderId());
        PaymentCancel attempt = findAttempt(target.cancelId());
        if (!order.isAwaitingCancelReconciliation() || !attempt.isAwaitingReconciliation()) {
            return skipped("취소 대사", order, attempt.getStatus());
        }
        attempt.confirmCompletedByReconciliation();
        order.confirmCanceledByReconciliation();
        findPayment(target.orderId()).cancel();
        productRepository.findByIdForUpdate(order.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                .increaseStock(order.getQuantity());
        return true;
    }

    /** PG에서 취소되지 않았다. 취소 시도 FAILED(PAYMENT_CANCEL_NOT_APPLIED), 주문 PAID. 재고는 그대로. */
    @Transactional
    public boolean confirmCancelNotApplied(CancelReconcileTarget target) {
        Order order = findOrderForUpdate(target.orderId());
        PaymentCancel attempt = findAttempt(target.cancelId());
        if (!order.isAwaitingCancelReconciliation() || !attempt.isAwaitingReconciliation()) {
            return skipped("취소 대사", order, attempt.getStatus());
        }
        attempt.confirmNotAppliedByReconciliation();
        order.confirmCancelNotAppliedByReconciliation();
        return true;
    }

    /** PG 조회가 NOT_FOUND_PAYMENT다. 주문 CANCEL_REVIEW, 취소 시도 UNKNOWN. 결제·재고는 그대로. 사람이 확인한다. */
    @Transactional
    public boolean markCancelReview(CancelReconcileTarget target) {
        Order order = findOrderForUpdate(target.orderId());
        PaymentCancel attempt = findAttempt(target.cancelId());
        if (!order.isAwaitingCancelReconciliation() || !attempt.isAwaitingReconciliation()) {
            return skipped("취소 대사", order, attempt.getStatus());
        }
        attempt.keepUnknownForReview();
        order.markCancelReviewByReconciliation();
        return true;
    }

    /**
     * 사람이 원인을 고친 뒤 호출한다. 주문 CANCEL_REVIEW → CANCEL_UNKNOWN. 확정은 다음 대사의 PG 조회로 한다.
     * {@code updated_at}이 지금으로 바뀌므로 대사 기준 시간이 지난 뒤 다시 조회된다.
     */
    @Transactional
    public void reopenCancelReview(Long orderId) {
        findOrderForUpdate(orderId).reopenCancelReview();
        log.warn("[취소 대사] 취소 확인 필요 건을 다시 대사 대상으로 되돌렸다. orderId={}", orderId);
    }

    private boolean skipped(String job, Order order, Enum<?> attemptStatus) {
        log.info("[{}] 고른 뒤 다른 경로가 먼저 확정했다. 건너뛴다. orderId={}, 주문 상태={}, 시도 상태={}",
                job, order.getId(), order.getStatus(), attemptStatus);
        return false;
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    private Payment findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 시도 기록이 없는 주문입니다. orderId=" + orderId));
    }

    private PaymentCancel findAttempt(Long cancelId) {
        return paymentCancelRepository.findById(cancelId)
                .orElseThrow(() -> new IllegalStateException("취소 시도 기록이 없습니다. cancelId=" + cancelId));
    }
}
