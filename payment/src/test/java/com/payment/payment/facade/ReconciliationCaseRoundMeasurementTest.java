package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PgPaymentStatus;
import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PaymentCancelStateService;
import com.payment.payment.service.PaymentReconciliationStateService;
import com.payment.payment.service.PessimisticLockPaymentStateService;
import com.payment.payment.service.ReconciliationProperties;
import com.payment.payment.service.StartCancelCommand;
import com.payment.payment.service.StartPayingCommand;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [측정 전용] phase-04 4-4: 대사의 모든 처리 경우를 한 회차씩 돌린다.
 * <p>
 * 조건은 {@link ReconciliationThroughputMeasurementTest}의 T0·F0와 같다 (대상 300건, 6분 경과, 수량 1,
 * PG 지연 0ms, 초당 1000, 회차 상한 50초, 묶음 500, 연속 실패 3). 바꾸는 것은 대상 상태와 PG 조회·취소 결과뿐이다.
 * 속도 제한과 PG 지연을 없애 케이스마다의 DB 처리 비용을 T0(2.4ms)·F0(3.0ms)와 비교한다. 운영 처리량이 아니다.
 * <p>
 * 실행: {@code ./gradlew measurementTest --tests "*ReconciliationCaseRoundMeasurementTest" -i}
 * <p>
 * 운영 설정으로 돌리기 (2026-11-26): 건수·초당 호출 수·PG 지연을 시스템 속성으로 바꾼다. 기본값은 위 조건(300 / 1000 / 0ms).
 * {@code ./gradlew measurementTest --tests "*ReconciliationCaseRoundMeasurementTest" -Dmeasure.count=50 -Dmeasure.perSecond=5 -Dmeasure.pgDelayMs=100 -i}
 */
@Slf4j
@Tag("measurement")
@TestMethodOrder(MethodOrderer.MethodName.class)
class ReconciliationCaseRoundMeasurementTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 1;
    private static final long SERVER_AMOUNT = PRICE * QUANTITY;
    private static final long WRONG_AMOUNT = 9_000L;
    private static final int COUNT = Integer.getInteger("measure.count", 300);
    private static final int CALLS_PER_SECOND = Integer.getInteger("measure.perSecond", 1000);
    private static final Duration PG_DELAY = Duration.ofMillis(Long.getLong("measure.pgDelayMs", 0L));
    private static final int AGED_MINUTES = 6;

    private static final List<String> ROWS = new ArrayList<>();

    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentClient fakePaymentClient;
    @Autowired private PessimisticLockPaymentStateService approveStateService;
    @Autowired private PaymentCancelStateService cancelStateService;
    @Autowired private PaymentReconciliationStateService reconciliationStateService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PaymentReconciliationFacade facade;
    private PaymentFacade approveFacade;
    private PaymentCancelFacade cancelFacade;
    private Product product;

    /** 대상 준비 방식 */
    private enum Target { PAYMENT_UNKNOWN, AGED_PAYING, COMPENSATION_FAILED_MISMATCH, COMPENSATION_FAILED_SAME, CANCEL_UNKNOWN, AGED_CANCELING }

    /** 회차가 어떻게 끝나야 하는가 */
    private enum Expect { ALL_APPLIED, ALL_SKIPPED, STOP_AFTER_3 }

    private enum Job { PAYMENT, CANCEL }

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        facade = new PaymentReconciliationFacade(reconciliationStateService, fakePaymentClient,
                new ReconciliationProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 500, Duration.ofSeconds(50), 3, CALLS_PER_SECOND));
        approveFacade = new PaymentFacade(approveStateService, fakePaymentClient);
        cancelFacade = new PaymentCancelFacade(cancelStateService, fakePaymentClient);
        product = productRepository.save(Product.create("키보드", PRICE, COUNT * QUANTITY + 100));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM payment_cancel");
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @AfterAll
    static void printTable() {
        log.info("[케이스 측정 결과] 대상 {}건, 초당 {}, PG 지연 {}ms\n| 코드 | 대상 | 조회 결과 | 대상 수 | 확정 | 건너뜀 | PG 실패 | 남은 대상 | 소요 | 건당 | PG 조회 | PG 취소 | 주문 상태 | 재고 |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n{}",
                COUNT, CALLS_PER_SECOND, PG_DELAY.toMillis(), String.join("\n", ROWS));
    }

    // ---- 승인 대사 ----

    @Test @DisplayName("A01 — PAYMENT_UNKNOWN 300건, 조회 DONE 금액 같음 → 300건 PAID")
    void a01() { run("A01", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "DONE 금액 같음", f -> f.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.PAID, 0); }

    @Test @DisplayName("A02 — 6분 넘은 PAYING 300건, 조회 DONE 금액 같음 → 300건 PAID")
    void a02() { run("A02", Job.PAYMENT, Target.AGED_PAYING, "DONE 금액 같음", f -> f.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.PAID, 0); }

    @Test @DisplayName("A03 — PAYMENT_UNKNOWN 300건, 조회 ABORTED → 300건 FAILED, 재고 +300")
    void a03() { run("A03", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "ABORTED", f -> f.willLookup(PgPaymentStatus.ABORTED, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A04 — PAYMENT_UNKNOWN 300건, 조회 EXPIRED → 300건 FAILED, 재고 +300")
    void a04() { run("A04", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "EXPIRED", f -> f.willLookup(PgPaymentStatus.EXPIRED, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A05 — PAYMENT_UNKNOWN 300건, 조회 READY → 300건 FAILED, 재고 +300")
    void a05() { run("A05", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "READY", f -> f.willLookup(PgPaymentStatus.READY, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A06 — PAYMENT_UNKNOWN 300건, 조회 IN_PROGRESS → 300건 FAILED, 재고 +300")
    void a06() { run("A06", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "IN_PROGRESS", f -> f.willLookup(PgPaymentStatus.IN_PROGRESS, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A07 — PAYMENT_UNKNOWN 300건, 조회 NOT_FOUND_PAYMENT → 300건 FAILED, 재고 +300")
    void a07() { run("A07", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "NOT_FOUND_PAYMENT", FakePaymentClient::willLookupNotFound, Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A08 — PAYMENT_UNKNOWN 300건, 조회 CANCELED → 300건 FAILED, 재고 +300")
    void a08() { run("A08", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "CANCELED", f -> f.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A09 — 보상 취소 실패 건(승인 9,000원) 300건, 조회 CANCELED → 300건 FAILED, 재고 +300, PG 취소 0번")
    void a09() { run("A09", Job.PAYMENT, Target.COMPENSATION_FAILED_MISMATCH, "CANCELED", f -> f.willLookup(PgPaymentStatus.CANCELED, WRONG_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A10 — 보상 취소 실패 건(승인 9,000원) 300건, 조회 DONE 9,000원 → 다시 취소 성공 → 300건 FAILED, 재고 +300, PG 취소 300번")
    void a10() { run("A10", Job.PAYMENT, Target.COMPENSATION_FAILED_MISMATCH, "DONE 금액 다름, 재취소 성공", f -> f.willLookup(PgPaymentStatus.DONE, WRONG_AMOUNT), Expect.ALL_APPLIED, OrderStatus.FAILED, COUNT); }

    @Test @DisplayName("A11 — 보상 취소 실패 건(승인 9,000원) 300건, 조회 DONE 9,000원 → 다시 취소 타임아웃 → 3건 후 회차 멈춤, 모두 PAYMENT_UNKNOWN")
    void a11() { run("A11", Job.PAYMENT, Target.COMPENSATION_FAILED_MISMATCH, "DONE 금액 다름, 재취소 타임아웃", f -> { f.willLookup(PgPaymentStatus.DONE, WRONG_AMOUNT); f.willTimeoutCancel(); }, Expect.STOP_AFTER_3, OrderStatus.PAYMENT_UNKNOWN, 0); }

    @Test @DisplayName("A12 — 보상 취소 실패 건(승인 금액 = 서버 금액) 300건, 조회 DONE 금액 같음 → 300건 PAID, PG 취소 0번")
    void a12() { run("A12", Job.PAYMENT, Target.COMPENSATION_FAILED_SAME, "DONE 금액 같음", f -> f.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.PAID, 0); }

    @Test @DisplayName("A13 — PAYMENT_UNKNOWN 300건, 조회 타임아웃 → 3건 후 회차 멈춤, 모두 PAYMENT_UNKNOWN")
    void a13() { run("A13", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "조회 타임아웃", FakePaymentClient::willTimeoutLookup, Expect.STOP_AFTER_3, OrderStatus.PAYMENT_UNKNOWN, 0); }

    @Test @DisplayName("A14 — PAYMENT_UNKNOWN 300건, 조회 거절(일반 404) → 300건 건너뜀, 회차는 끝까지 돎")
    void a14() { run("A14", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "조회 거절(404)", FakePaymentClient::willRejectLookup, Expect.ALL_SKIPPED, OrderStatus.PAYMENT_UNKNOWN, 0); }

    @Test @DisplayName("A15 — PAYMENT_UNKNOWN 300건, 조회 WAITING_FOR_DEPOSIT → 300건 건너뜀")
    void a15() { run("A15", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "WAITING_FOR_DEPOSIT", f -> f.willLookup(PgPaymentStatus.WAITING_FOR_DEPOSIT, SERVER_AMOUNT), Expect.ALL_SKIPPED, OrderStatus.PAYMENT_UNKNOWN, 0); }

    @Test @DisplayName("A16 — PAYMENT_UNKNOWN 300건, 조회 PARTIAL_CANCELED → 300건 건너뜀")
    void a16() { run("A16", Job.PAYMENT, Target.PAYMENT_UNKNOWN, "PARTIAL_CANCELED", f -> f.willLookup(PgPaymentStatus.PARTIAL_CANCELED, SERVER_AMOUNT), Expect.ALL_SKIPPED, OrderStatus.PAYMENT_UNKNOWN, 0); }

    // ---- 취소 대사 ----

    @Test @DisplayName("C01 — CANCEL_UNKNOWN 300건, 조회 CANCELED → 300건 CANCELED, 재고 +300")
    void c01() { run("C01", Job.CANCEL, Target.CANCEL_UNKNOWN, "CANCELED", f -> f.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.CANCELED, COUNT); }

    @Test @DisplayName("C02 — 6분 넘은 CANCELING 300건, 조회 CANCELED → 300건 CANCELED, 재고 +300")
    void c02() { run("C02", Job.CANCEL, Target.AGED_CANCELING, "CANCELED", f -> f.willLookup(PgPaymentStatus.CANCELED, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.CANCELED, COUNT); }

    @Test @DisplayName("C03 — CANCEL_UNKNOWN 300건, 조회 DONE → 300건 PAID로 돌아감")
    void c03() { run("C03", Job.CANCEL, Target.CANCEL_UNKNOWN, "DONE", f -> f.willLookup(PgPaymentStatus.DONE, SERVER_AMOUNT), Expect.ALL_APPLIED, OrderStatus.PAID, 0); }

    @Test @DisplayName("C04 — CANCEL_UNKNOWN 300건, 조회 NOT_FOUND_PAYMENT → 300건 CANCEL_REVIEW")
    void c04() { run("C04", Job.CANCEL, Target.CANCEL_UNKNOWN, "NOT_FOUND_PAYMENT", FakePaymentClient::willLookupNotFound, Expect.ALL_APPLIED, OrderStatus.CANCEL_REVIEW, 0); }

    @Test @DisplayName("C05 — CANCEL_UNKNOWN 300건, 조회 타임아웃 → 3건 후 회차 멈춤, 모두 CANCEL_UNKNOWN")
    void c05() { run("C05", Job.CANCEL, Target.CANCEL_UNKNOWN, "조회 타임아웃", FakePaymentClient::willTimeoutLookup, Expect.STOP_AFTER_3, OrderStatus.CANCEL_UNKNOWN, 0); }

    @Test @DisplayName("C06 — CANCEL_UNKNOWN 300건, 조회 거절(일반 404) → 300건 건너뜀, 회차는 끝까지 돎")
    void c06() { run("C06", Job.CANCEL, Target.CANCEL_UNKNOWN, "조회 거절(404)", FakePaymentClient::willRejectLookup, Expect.ALL_SKIPPED, OrderStatus.CANCEL_UNKNOWN, 0); }

    @Test @DisplayName("C07 — CANCEL_UNKNOWN 300건, 조회 ABORTED(예상 밖 상태) → 300건 건너뜀")
    void c07() { run("C07", Job.CANCEL, Target.CANCEL_UNKNOWN, "ABORTED", f -> f.willLookup(PgPaymentStatus.ABORTED, SERVER_AMOUNT), Expect.ALL_SKIPPED, OrderStatus.CANCEL_UNKNOWN, 0); }

    // ---- 실행 ----

    private void run(String code, Job job, Target target, String lookupLabel, Consumer<FakePaymentClient> pg,
                     Expect expect, OrderStatus expectedStatus, int expectedStockIncrease) {
        // given
        prepare(target);
        fakePaymentClient.reset();
        pg.accept(fakePaymentClient);
        fakePaymentClient.delay(PG_DELAY); // 대상을 다 만든 뒤에 건다 (승인에도 적용되므로)
        int stockBefore = stock();

        // when
        ReconciliationRoundResult r = job == Job.PAYMENT ? facade.reconcilePayments() : facade.reconcileCancels();

        // then
        int stockAfter = stock();
        Map<String, Integer> statuses = jdbcTemplate.queryForList("SELECT status, COUNT(*) c FROM orders GROUP BY status").stream()
                .collect(Collectors.toMap(m -> (String) m.get("status"), m -> ((Number) m.get("c")).intValue()));
        double perTarget = r.targets() == 0 ? 0 : (double) r.elapsedMs() / r.targets();
        ROWS.add(String.format("| %s | %s | %s | %d | %d | %d | %d | %s | %dms | %.1fms | %d | %d | %s | %d → %d |",
                code, target, lookupLabel, r.targets(), r.applied(), r.skipped(), r.pgFailures(), r.hasMore() ? "있음" : "없음",
                r.elapsedMs(), perTarget, fakePaymentClient.lookupCallCount(), fakePaymentClient.cancelCallCount(),
                statuses, stockBefore, stockAfter));
        log.info("[측정 {}] {}", code, ROWS.getLast());

        assertThat(r.errors()).as("반영 오류 없음").isZero();
        switch (expect) {
            case ALL_APPLIED -> {
                assertThat(r.applied()).isEqualTo(COUNT);
                assertThat(statuses.get(expectedStatus.name())).isEqualTo(COUNT);
                assertThat(r.hasMore()).isFalse();
            }
            case ALL_SKIPPED -> {
                assertThat(r.targets()).as("회차가 멈추지 않고 모두 본다").isEqualTo(COUNT);
                assertThat(r.skipped()).isEqualTo(COUNT);
                assertThat(r.pgFailures()).isZero();
                assertThat(statuses.get(expectedStatus.name())).isEqualTo(COUNT);
            }
            case STOP_AFTER_3 -> {
                assertThat(r.targets()).isEqualTo(3);
                assertThat(r.pgFailures()).isEqualTo(3);
                assertThat(r.hasMore()).isTrue();
                assertThat(statuses.get(expectedStatus.name())).isEqualTo(COUNT);
            }
        }
        assertThat(stockAfter).isEqualTo(stockBefore + expectedStockIncrease * QUANTITY);
    }

    // ---- 대상 만들기 (실제 흐름으로 만든 뒤 updated_at만 옮긴다) ----

    private void prepare(Target target) {
        for (int i = 0; i < COUNT; i++) {
            Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
            switch (target) {
                case PAYMENT_UNKNOWN -> {
                    fakePaymentClient.willTimeout();
                    catchThrowable(() -> approve(order));
                }
                case AGED_PAYING -> approveStateService.startPaying(new StartPayingCommand(
                        order.getId(), "key-" + order.getId(), order.getTotalPrice(), "payment-key-" + order.getId()));
                case COMPENSATION_FAILED_MISMATCH, COMPENSATION_FAILED_SAME -> {
                    fakePaymentClient.willApproveWithAmount(WRONG_AMOUNT);
                    fakePaymentClient.willTimeoutCancel();
                    catchThrowable(() -> approve(order));
                }
                case CANCEL_UNKNOWN -> {
                    fakePaymentClient.reset();
                    approve(order);
                    fakePaymentClient.willTimeoutCancel();
                    catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
                }
                case AGED_CANCELING -> {
                    fakePaymentClient.reset();
                    approve(order);
                    cancelStateService.startCanceling(new StartCancelCommand(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));
                }
            }
        }
        if (target == Target.COMPENSATION_FAILED_SAME) {
            jdbcTemplate.update("UPDATE payment SET approved_amount = ?", SERVER_AMOUNT);
        }
        if (target == Target.COMPENSATION_FAILED_MISMATCH || target == Target.COMPENSATION_FAILED_SAME) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment WHERE failure_code = ?", Integer.class,
                    ErrorCode.PAYMENT_CANCEL_FAILED.name())).as("준비 확인").isEqualTo(COUNT);
        }
        jdbcTemplate.update("UPDATE orders SET updated_at = ?", LocalDateTime.now().minusMinutes(AGED_MINUTES));
    }

    private void approve(Order order) {
        approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(), "payment-key-" + order.getId());
    }

    private int stock() {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
