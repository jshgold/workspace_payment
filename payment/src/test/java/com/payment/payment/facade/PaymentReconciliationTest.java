package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDateTime;

import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgPaymentStatus;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PaymentCancelStateService;
import com.payment.payment.service.PessimisticLockPaymentStateService;
import com.payment.payment.service.StartCancelCommand;
import com.payment.payment.service.StartPayingCommand;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * phase-04 4-4 대사의 재현 테스트. <b>구현 후의 올바른 동작</b>을 기대한다.
 * 구현 전에는 실패하는 것이 정상이고, 결과는 {@code docs/evidence/before-reconciliation.md}에 남긴다.
 * <p>
 * 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (서버 금액 20,000원, 결제 시작 후 재고 3). 가짜 PG.
 * 대상 상태는 실제 흐름(PaymentFacade, 상태 서비스)으로 만들고, 시간 경과는 {@code updated_at}을 과거로 옮겨 만든다.
 * 준비가 끝나면 가짜 PG를 초기화해 호출 횟수는 대사가 부른 것만 센다.
 */
class PaymentReconciliationTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int STOCK = 5;
    private static final int QUANTITY = 2;
    private static final long SERVER_AMOUNT = PRICE * QUANTITY;

    /** 대사 기준 5분(intent 8절)을 넘긴 시간 */
    private static final int AGED_MINUTES = 6;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private FakePaymentClient fakePaymentClient;

    @Autowired
    private PessimisticLockPaymentStateService approveStateService;

    @Autowired
    private PaymentCancelStateService cancelStateService;

    @Autowired
    private PaymentReconciliationFacade reconciliationFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentFacade approveFacade;
    private PaymentCancelFacade cancelFacade;

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        approveFacade = new PaymentFacade(approveStateService, fakePaymentClient);
        cancelFacade = new PaymentCancelFacade(cancelStateService, fakePaymentClient);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM payment_cancel");
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Nested
    @DisplayName("승인 대사")
    class ReconcilePayments {

        @Test
        @DisplayName("R1 — 상품 1 × 재고 5, 수량 2, 승인 타임아웃으로 PAYMENT_UNKNOWN 6분 경과, PG 조회 DONE 20,000원 → 주문 PAID, 결제 APPROVED, 재고 3 유지, 승인 재요청 없음")
        void unknownApprovedBecomesPaid() {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(stock(product)).as("선차감이므로 PAID 확정에서 재고를 건드리지 않는다").isEqualTo(3);
            assertThat(fakePaymentClient.approveCallCount()).as("대사는 조회만 한다").isZero();
            assertThat(fakePaymentClient.lookupCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("R2 — PAYMENT_UNKNOWN 6분 경과, PG 조회 ABORTED → 주문 FAILED, 결제 FAILED, 재고 5로 복구")
        void unknownAbortedBecomesFailed() {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.ABORTED, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(stock(product)).isEqualTo(STOCK);
            assertThat(fakePaymentClient.approveCallCount()).isZero();
        }

        @Test
        @DisplayName("R3 — PAYING 6분 경과 주문과 1분 경과 주문, PG 조회 DONE 20,000원 → 6분 주문만 PAID, 1분 주문은 대상이 아니라 PAYING 그대로이고 조회도 1번")
        void onlyAgedPayingIsReconciled() {
            // given
            Product product = saveProduct(10);
            Order aged = payingOrder(product);
            age(aged, AGED_MINUTES);
            Order recent = payingOrder(product);
            age(recent, 1);
            fakePaymentClient.reset();
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(aged)).isEqualTo(OrderStatus.PAID);
            assertThat(payment(aged).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(orderStatus(recent)).as("응답을 기다리는 중일 수 있는 결제는 건드리지 않는다").isEqualTo(OrderStatus.PAYING);
            assertThat(payment(recent).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(fakePaymentClient.lookupCallCount()).isEqualTo(1);
            assertThat(stock(product)).as("두 주문 모두 선차감 상태 그대로").isEqualTo(10 - QUANTITY * 2);
        }

        @Test
        @DisplayName("R4 — PAYMENT_UNKNOWN 6분 경과, PG 조회 NOT_FOUND_PAYMENT → 결제 쪽 장애로 보고 주문 FAILED, 결제 FAILED, 재고 5로 복구")
        void unknownNotFoundBecomesFailed() {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookupNotFound();

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(stock(product)).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("R5 — PAYMENT_UNKNOWN 6분 경과, PG 조회 타임아웃 → 아무것도 바꾸지 않음 (주문 PAYMENT_UNKNOWN, 결제 UNKNOWN, 재고 3), 다음 회차에 다시 조회")
        void lookupTimeoutChangesNothing() {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willTimeoutLookup();

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(stock(product)).isEqualTo(3);
            assertThat(fakePaymentClient.lookupCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("R6 — 보상 취소 실패 건(UNKNOWN + PAYMENT_CANCEL_FAILED) 6분 경과, PG 조회 DONE 15,000원(서버 20,000원과 다름) → 다시 취소 1번, 주문 FAILED, 결제 FAILED + PAYMENT_AMOUNT_MISMATCH, 재고 5")
        void cancelFailedWithDifferentAmountIsCanceledAgain() {
            // given
            Product product = saveProduct();
            Order order = compensationFailedOrder(product, 15_000L);
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, 15_000L);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.cancelCallCount()).as("받기로 한 금액이 아니므로 돌려준다").isEqualTo(1);
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
            Payment payment = payment(order);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH.name());
            assertThat(stock(product)).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("R7 — 보상 취소 실패 건 6분 경과, PG 조회 CANCELED(앞선 보상 취소가 실제로는 성공) → 다시 취소하지 않고 주문 FAILED, 결제 FAILED, 재고 5")
        void cancelFailedButActuallyCanceledBecomesFailed() {
            // given
            Product product = saveProduct();
            Order order = compensationFailedOrder(product, 15_000L);
            fakePaymentClient.willLookup(PgPaymentStatus.CANCELED, 15_000L);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.cancelCallCount()).isZero();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(stock(product)).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("R8 — 보상 취소 실패 건(반영 실패로 보상하려던 건) 6분 경과, PG 조회 DONE 20,000원(서버 금액과 같음) → 정상 결제이므로 다시 취소하지 않고 주문 PAID, 결제 APPROVED, 재고 3")
        void cancelFailedWithSameAmountBecomesPaid() {
            // given: 반영 실패로 보상 취소하려다 실패한 건. 승인 금액은 서버 금액과 같다.
            Product product = saveProduct();
            Order order = compensationFailedOrder(product, 15_000L);
            jdbcTemplate.update("UPDATE payment SET approved_amount = ? WHERE order_id = ?", SERVER_AMOUNT, order.getId());
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.cancelCallCount()).isZero();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(stock(product)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("취소 대사")
    class ReconcileCancels {

        @Test
        @DisplayName("C1 — 취소 타임아웃으로 CANCEL_UNKNOWN 6분 경과, PG 조회 CANCELED → 주문 CANCELED, 결제 CANCELED, 취소 시도 COMPLETED, 재고 5로 복구")
        void cancelUnknownCanceledBecomesCanceled() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("COMPLETED");
            assertThat(stock(product)).isEqualTo(STOCK);
            assertThat(fakePaymentClient.cancelCallCount()).as("대사는 조회만 한다").isZero();
        }

        @Test
        @DisplayName("C2 — CANCEL_UNKNOWN 6분 경과, PG 조회 DONE(취소되지 않음) → 주문 PAID로 돌아감, 결제 APPROVED, 취소 시도 FAILED, 재고 3")
        void cancelUnknownNotCanceledRevertsToPaid() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("FAILED");
            assertThat(stock(product)).isEqualTo(3);
        }

        @Test
        @DisplayName("C3 — 서버 중단 추정: CANCELING 6분 경과, PG 조회 CANCELED → 주문 CANCELED, 결제 CANCELED, 취소 시도 COMPLETED, 재고 5")
        void agedCancelingCanceledBecomesCanceled() {
            // given
            Product product = saveProduct();
            Order order = paidOrder(product);
            cancelStateService.startCanceling(new StartCancelCommand(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
            age(order, AGED_MINUTES);
            fakePaymentClient.reset();
            fakePaymentClient.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("COMPLETED");
            assertThat(stock(product)).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("C4 — CANCEL_UNKNOWN 6분 경과, PG 조회 NOT_FOUND_PAYMENT → 시스템 장애이므로 아무것도 바꾸지 않음 (주문 CANCEL_UNKNOWN, 취소 시도 UNKNOWN, 재고 3)")
        void cancelUnknownNotFoundChangesNothing() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookupNotFound();

            // when
            Throwable thrown = catchThrowable(() -> reconciliationFacade.reconcileCancels());

            // then
            assertThat(thrown).as("건 하나의 장애로 회차가 예외로 끝나지 않는다").isNull();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_UNKNOWN);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("UNKNOWN");
            assertThat(stock(product)).isEqualTo(3);
        }
    }

    // ---- 대상 상태 만들기 (실제 흐름으로 만든 뒤 시간만 옮긴다) ----

    /** 승인 타임아웃 → PAYMENT_UNKNOWN / UNKNOWN, 재고 3 */
    private Order paymentUnknownOrder(Product product) {
        Order order = saveOrder(product);
        fakePaymentClient.willTimeout();
        catchThrowable(() -> approve(order));
        age(order, AGED_MINUTES);
        fakePaymentClient.reset();
        return order;
    }

    /** 결제 시작만 커밋되고 PG 호출 결과가 기록되지 않음(서버 중단) → PAYING / REQUESTED, 재고 차감 */
    private Order payingOrder(Product product) {
        Order order = saveOrder(product);
        approveStateService.startPaying(new StartPayingCommand(
                order.getId(), "key-" + order.getId(), order.getTotalPrice(), "payment-key-" + order.getId()));
        return order;
    }

    /** 다른 금액으로 승인 → 보상 취소 타임아웃 → PAYMENT_UNKNOWN / UNKNOWN + PAYMENT_CANCEL_FAILED, 재고 3 */
    private Order compensationFailedOrder(Product product, long approvedAmount) {
        Order order = saveOrder(product);
        fakePaymentClient.willApproveWithAmount(approvedAmount);
        fakePaymentClient.willTimeoutCancel();
        catchThrowable(() -> approve(order));
        assertThat(payment(order).getFailureCode()).as("준비 확인").isEqualTo(ErrorCode.PAYMENT_CANCEL_FAILED.name());
        age(order, AGED_MINUTES);
        fakePaymentClient.reset();
        return order;
    }

    private Order paidOrder(Product product) {
        Order order = saveOrder(product);
        approve(order);
        return order;
    }

    /** 승인 → 취소 타임아웃 → CANCEL_UNKNOWN / 결제 APPROVED / 취소 시도 UNKNOWN, 재고 3 */
    private Order cancelUnknownOrder(Product product) {
        Order order = paidOrder(product);
        fakePaymentClient.willTimeoutCancel();
        catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
        age(order, AGED_MINUTES);
        fakePaymentClient.reset();
        return order;
    }

    private void approve(Order order) {
        approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                "payment-key-" + order.getId());
    }

    /** 시각은 JVM 기준으로 넣는다. (엔티티의 @UpdateTimestamp와 같은 시계) */
    private void age(Order order, int minutes) {
        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(minutes), order.getId());
    }

    private Product saveProduct() {
        return saveProduct(STOCK);
    }

    private Product saveProduct(int stock) {
        return productRepository.save(Product.create("키보드", PRICE, stock));
    }

    private Order saveOrder(Product product) {
        return orderRepository.save(Order.create(100L, product, QUANTITY));
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private Payment payment(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow();
    }

    private String cancelAttemptStatus(Order order) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_cancel WHERE order_id = ?", String.class, order.getId());
    }
}
