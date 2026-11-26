package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDateTime;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgPaymentStatus;
import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PaymentCancelStateService;
import com.payment.payment.service.PaymentReconciliationStateService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    private PaymentReconciliationStateService reconciliationStateService;

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
            // 2026-11-26부터 반영 실패는 보상 취소하지 않으므로 새로 생기지 않는다. 그 전에 남은 건을 대사가 처리하는지 본다.
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

        @ParameterizedTest(name = "R9 — PG 조회 {0}")
        @EnumSource(value = PgPaymentStatus.class, names = {"EXPIRED", "READY", "IN_PROGRESS"})
        @DisplayName("R9 — PAYMENT_UNKNOWN 6분 경과, PG 조회 EXPIRED / READY / IN_PROGRESS(승인 안 됨) → 주문 FAILED, 결제 FAILED + PAYMENT_NOT_APPROVED, 재고 5로 복구, 승인 재요청 없음")
        void unknownNotApprovedBecomesFailed(PgPaymentStatus lookupStatus) {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookup(lookupStatus, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
            Payment payment = payment(order);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo(ErrorCode.PAYMENT_NOT_APPROVED.name());
            assertThat(stock(product)).isEqualTo(STOCK);
            assertThat(fakePaymentClient.approveCallCount()).as("대사는 승인 요청을 다시 보내지 않는다").isZero();
        }

        @Test
        @DisplayName("R10 — 보상 취소 실패 건 6분 경과, PG 조회 DONE 15,000원(서버 20,000원과 다름), 다시 취소도 타임아웃 → 다시 취소 1번, 주문 PAYMENT_UNKNOWN, 결제 UNKNOWN + PAYMENT_CANCEL_FAILED, 승인 금액 15,000 유지, 재고 3 (다음 회차에 다시 봄)")
        void cancelAgainFailsKeepsUnknown() {
            // given
            Product product = saveProduct();
            Order order = compensationFailedOrder(product, 15_000L);
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, 15_000L);
            fakePaymentClient.willTimeoutCancel();

            // when
            ReconciliationRoundResult result = reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
            Payment payment = payment(order);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(payment.getFailureCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_FAILED.name());
            assertThat(payment.getApprovedAmount()).isEqualTo(15_000L);
            assertThat(stock(product)).as("돈이 묶였는지 모르므로 재고를 돌려주지 않는다").isEqualTo(3);
            assertThat(result.pgFailures()).as("재취소 실패는 PG 실패로 센다").isEqualTo(1);
        }

        @ParameterizedTest(name = "R11 — PG 조회 {0}")
        @EnumSource(value = PgPaymentStatus.class, names = {"WAITING_FOR_DEPOSIT", "PARTIAL_CANCELED"})
        @DisplayName("R11 — PAYMENT_UNKNOWN 6분 경과, PG 조회 WAITING_FOR_DEPOSIT / PARTIAL_CANCELED(이 서비스에서 생길 수 없음) → 회차는 예외 없이 끝나고 아무것도 바꾸지 않음 (주문 PAYMENT_UNKNOWN, 결제 UNKNOWN, 재고 3)")
        void unexpectedLookupStatusChangesNothing(PgPaymentStatus lookupStatus) {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookup(lookupStatus, SERVER_AMOUNT);

            // when
            Throwable thrown = catchThrowable(() -> reconciliationFacade.reconcilePayments());

            // then
            assertThat(thrown).isNull();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(stock(product)).isEqualTo(3);
            assertThat(fakePaymentClient.cancelCallCount()).isZero();
        }

        @Test
        @DisplayName("R13 — PG가 20,000원 그대로 승인했는데 반영 실패 → 보상 취소 없이 PAYMENT_UNKNOWN / UNKNOWN(failure_code 없음, 승인 금액 20,000), 6분 경과, PG 조회 DONE 20,000원 → 주문 PAID, 결제 APPROVED, failure_code 없음, 재고 3, PG 취소 0번")
        void applyFailedUnknownBecomesPaid() {
            // given: PaymentFacade가 반영 실패 때 남기는 기록을 같은 메서드로 만든다
            Product product = saveProduct();
            Order order = payingOrder(product);
            approveStateService.markUnknown(order.getId(), null, SERVER_AMOUNT);
            age(order, AGED_MINUTES);
            assertThat(payment(order).getStatus()).as("준비 확인").isEqualTo(PaymentStatus.UNKNOWN);
            fakePaymentClient.reset();
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);

            // when
            reconciliationFacade.reconcilePayments();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
            Payment payment = payment(order);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(payment.getFailureCode()).isNull();
            assertThat(stock(product)).as("선차감 그대로").isEqualTo(3);
            assertThat(fakePaymentClient.cancelCallCount()).as("고객이 동의한 결제를 되돌리지 않는다").isZero();
            assertThat(fakePaymentClient.approveCallCount()).isZero();
        }

        @Test
        @DisplayName("R15 — 승인 타임아웃으로 PAYMENT_UNKNOWN 6분 경과, PG 조회 ABORTED로 대사가 FAILED 확정 → 같은 멱등키로 다시 승인 요청하면 409 PAYMENT_NOT_APPROVED, PG 승인 0번")
        void sameKeyAfterReconciledFailureGetsNotApproved() {
            // given
            Product product = saveProduct();
            Order order = paymentUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.ABORTED, SERVER_AMOUNT);
            reconciliationFacade.reconcilePayments();
            fakePaymentClient.reset();

            // when
            Throwable thrown = catchThrowable(() -> approve(order));

            // then
            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_APPROVED));
            assertThat(fakePaymentClient.approveCallCount()).isZero();
        }

        @Test
        @DisplayName("R14 — 상품 1 × 재고 10, PAYMENT_UNKNOWN 6분 경과 주문 4개, PG 조회가 모두 거절(일반 404 NOT_FOUND) → 회차가 멈추지 않고 4개 모두 조회, 4개 모두 건너뜀(PG 실패 0), 상태·재고 그대로")
        void rejectedLookupIsSkippedWithoutStoppingRound() {
            // given
            Product product = saveProduct(10);
            Order[] orders = {paymentUnknownOrder(product), paymentUnknownOrder(product),
                    paymentUnknownOrder(product), paymentUnknownOrder(product)};
            fakePaymentClient.willRejectLookup();

            // when
            ReconciliationRoundResult result = reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.lookupCallCount()).as("거절은 연속 실패에 세지 않으므로 4번째도 조회한다").isEqualTo(4);
            assertThat(result.skipped()).isEqualTo(4);
            assertThat(result.pgFailures()).isZero();
            for (Order order : orders) {
                assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
                assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            }
            assertThat(stock(product)).isEqualTo(10 - QUANTITY * 4);
        }

        @Test
        @DisplayName("R12 — 상품 1 × 재고 10, PAYMENT_UNKNOWN 6분 경과 주문 4개, PG 조회 계속 타임아웃 → 3번 연속 실패에서 회차 중단: PG 조회 3번, 남은 대상 있음, 4개 모두 PAYMENT_UNKNOWN, 재고 2")
        void stopsRoundAfterConsecutiveLookupFailures() {
            // given
            Product product = saveProduct(10);
            Order o1 = paymentUnknownOrder(product);
            Order o2 = paymentUnknownOrder(product);
            Order o3 = paymentUnknownOrder(product);
            Order o4 = paymentUnknownOrder(product);
            fakePaymentClient.willTimeoutLookup();

            // when
            ReconciliationRoundResult result = reconciliationFacade.reconcilePayments();

            // then
            assertThat(fakePaymentClient.lookupCallCount()).as("4번째 주문은 조회하지 않는다").isEqualTo(3);
            assertThat(result.targets()).isEqualTo(3);
            assertThat(result.pgFailures()).isEqualTo(3);
            assertThat(result.hasMore()).isTrue();
            for (Order order : new Order[]{o1, o2, o3, o4}) {
                assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
            }
            assertThat(stock(product)).isEqualTo(10 - QUANTITY * 4);
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
        @DisplayName("C10 — CANCEL_UNKNOWN 6분 경과, PG 조회 DONE으로 대사가 '취소 안 됨' 확정 → 취소 시도 FAILED + PAYMENT_CANCEL_NOT_APPLIED, 같은 키로 다시 취소하면 409 PAYMENT_CANCEL_NOT_APPLIED(PG 취소 0번), 새 키로 취소하면 새 시도로 CANCELED")
        void cancelNotAppliedReplayAndNewKey() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT);
            reconciliationFacade.reconcileCancels();
            fakePaymentClient.reset();

            // when
            Throwable sameKey = catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
            int cancelCallsAfterSameKey = fakePaymentClient.cancelCallCount();
            cancelFacade.cancel(order.getId(), "cancel-key-new", "단순 변심");

            // then
            assertThat(jdbcTemplate.queryForObject("SELECT failure_code FROM payment_cancel WHERE idempotency_key = ?",
                    String.class, "cancel-key-" + order.getId())).isEqualTo(ErrorCode.PAYMENT_CANCEL_NOT_APPLIED.name());
            assertThat(sameKey).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_NOT_APPLIED));
            assertThat(cancelCallsAfterSameKey).as("같은 키 재요청은 PG를 부르지 않는다").isZero();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
            assertThat(stock(product)).isEqualTo(STOCK);
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
        @DisplayName("C4 — CANCEL_UNKNOWN 6분 경과, PG 조회 NOT_FOUND_PAYMENT → 시스템 장애이므로 주문 CANCEL_REVIEW(사람 확인)로 빼고, 결제 APPROVED, 취소 시도 UNKNOWN, 재고 3 그대로, 다음 회차 대상에서 빠짐")
        void cancelUnknownNotFoundMovesToReview() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookupNotFound();

            // when
            Throwable thrown = catchThrowable(() -> reconciliationFacade.reconcileCancels());
            ReconciliationRoundResult next = reconciliationFacade.reconcileCancels();

            // then
            assertThat(thrown).as("건 하나의 장애로 회차가 예외로 끝나지 않는다").isNull();
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_REVIEW);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("UNKNOWN");
            assertThat(stock(product)).isEqualTo(3);
            assertThat(next.targets()).as("CANCEL_REVIEW는 대사 대상이 아니다").isZero();
            assertThat(fakePaymentClient.lookupCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("C7 — 서버 중단 추정: CANCELING 6분 경과(취소 시도 REQUESTED), PG 조회 NOT_FOUND_PAYMENT → 주문 CANCEL_REVIEW, 취소 시도 UNKNOWN, 결제 APPROVED, 재고 3")
        void agedCancelingNotFoundMovesToReview() {
            // given
            Product product = saveProduct();
            Order order = paidOrder(product);
            cancelStateService.startCanceling(new StartCancelCommand(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
            age(order, AGED_MINUTES);
            fakePaymentClient.reset();
            fakePaymentClient.willLookupNotFound();

            // when
            reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_REVIEW);
            assertThat(cancelAttemptStatus(order)).isEqualTo("UNKNOWN");
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(stock(product)).isEqualTo(3);
        }

        @Test
        @DisplayName("C8 — CANCEL_REVIEW 주문을 사람이 되돌림(수동) → CANCEL_UNKNOWN, 5분 경과 후 PG 조회 CANCELED → 주문 CANCELED, 결제 CANCELED, 취소 시도 COMPLETED, 재고 5")
        void reopenedReviewIsReconciledAgain() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookupNotFound();
            reconciliationFacade.reconcileCancels();

            // when
            reconciliationStateService.reopenCancelReview(order.getId());
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_UNKNOWN);
            age(order, AGED_MINUTES);
            fakePaymentClient.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT);
            reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("COMPLETED");
            assertThat(stock(product)).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("C9 — CANCEL_REVIEW 주문에 새 키로 취소 요청 → 409 PAYMENT_IN_PROGRESS, 취소 시도 기록 추가 없음, PG 취소 0번")
        void cancelRequestOnReviewIsInProgress() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willLookupNotFound();
            reconciliationFacade.reconcileCancels();
            fakePaymentClient.reset();

            // when
            Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-new", "단순 변심"));

            // then
            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_cancel WHERE order_id = ?",
                    Integer.class, order.getId())).isEqualTo(1);
            assertThat(fakePaymentClient.cancelCallCount()).isZero();
        }

        @Test
        @DisplayName("C6 — CANCEL_UNKNOWN 6분 경과, PG 조회 거절(일반 404 NOT_FOUND) → 회차는 예외 없이 끝나고 아무것도 바꾸지 않음, 건너뜀 1건, PG 실패 0건")
        void cancelLookupRejectedIsSkipped() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willRejectLookup();

            // when
            ReconciliationRoundResult result = reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_UNKNOWN);
            assertThat(cancelAttemptStatus(order)).isEqualTo("UNKNOWN");
            assertThat(stock(product)).isEqualTo(3);
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.pgFailures()).isZero();
        }

        @Test
        @DisplayName("C5 — CANCEL_UNKNOWN 6분 경과, PG 조회 타임아웃 → 아무것도 바꾸지 않음 (주문 CANCEL_UNKNOWN, 결제 APPROVED, 취소 시도 UNKNOWN, 재고 3), PG 실패 1건, 다음 회차에 다시 조회")
        void cancelLookupTimeoutChangesNothing() {
            // given
            Product product = saveProduct();
            Order order = cancelUnknownOrder(product);
            fakePaymentClient.willTimeoutLookup();

            // when
            ReconciliationRoundResult result = reconciliationFacade.reconcileCancels();

            // then
            assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_UNKNOWN);
            assertThat(payment(order).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(cancelAttemptStatus(order)).isEqualTo("UNKNOWN");
            assertThat(stock(product)).isEqualTo(3);
            assertThat(fakePaymentClient.lookupCallCount()).isEqualTo(1);
            assertThat(fakePaymentClient.cancelCallCount()).as("대사는 취소를 다시 보내지 않는다").isZero();
            assertThat(result.pgFailures()).isEqualTo(1);
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
