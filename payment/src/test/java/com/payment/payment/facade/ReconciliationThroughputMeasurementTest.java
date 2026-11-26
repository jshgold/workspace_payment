package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PgPaymentStatus;
import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PaymentReconciliationStateService;
import com.payment.payment.service.PessimisticLockPaymentStateService;
import com.payment.payment.service.ReconciliationProperties;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import lombok.extern.slf4j.Slf4j;
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
 * [측정 전용] phase-04 4-4: 승인 대사 한 회차가 몇 건을 처리하는지 잰다. (docs/payment-reconciliation-discussion.md S)
 * <p>
 * 초당 5건 × 50초면 한 회차 최대 약 250건으로, 묶음 크기 500과 맞지 않을 수 있다. 이 계산이 맞는지,
 * PG가 느릴 때 몇 건으로 떨어지는지 확인한다.
 * <p>
 * 시나리오마다 {@link ReconciliationProperties}를 바꿔 {@link PaymentReconciliationFacade}를 직접 만든다.
 * (운영 설정 파일은 건드리지 않는다) 결과는 {@code docs/evidence/reconciliation-throughput.md}에 옮긴다.
 * 검증은 정합성(확정 건수, 주문 상태)만 하고, 처리 건수·소요 시간은 기록만 한다.
 */
@Slf4j
@Tag("measurement")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationThroughputMeasurementTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 1;
    private static final int TARGET_COUNT = 300;
    private static final Duration MAX_ROUND = Duration.ofSeconds(50);

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
    private PaymentReconciliationStateService reconciliationStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<Order> orders;

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        orders = createPaymentUnknownOrders(TARGET_COUNT, 6);
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @org.junit.jupiter.api.Order(0)
    @DisplayName("T0 — PAYMENT_UNKNOWN 6분 경과 300건, PG 조회 DONE(금액 일치), PG 지연 0ms, 속도 제한 초당 1000(사실상 없음), 회차 상한 50초 → 건당 DB 처리 비용")
    void t0_dbCostOnly() {
        measure("T0", Duration.ZERO, 1000);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("T1 — PAYMENT_UNKNOWN 6분 경과 300건, PG 조회 DONE(금액 일치), PG 지연 0ms, 초당 5, 회차 상한 50초 → 속도 제한이 처리량 상한을 정하는지")
    void t1_rateLimitBound() {
        measure("T1", Duration.ZERO, 5);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("T2 — PAYMENT_UNKNOWN 6분 경과 300건, PG 조회 DONE(금액 일치), PG 지연 100ms, 초당 5, 회차 상한 50초 → 지연 < 호출 간격(200ms)")
    void t2_latencyBelowInterval() {
        measure("T2", Duration.ofMillis(100), 5);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("T3 — PAYMENT_UNKNOWN 6분 경과 300건, PG 조회 DONE(금액 일치), PG 지연 500ms, 초당 5, 회차 상한 50초 → 지연 > 호출 간격(200ms)")
    void t3_latencyAboveInterval() {
        measure("T3", Duration.ofMillis(500), 5);
    }

    private void measure(String code, Duration pgDelay, int callsPerSecond) {
        // given
        PaymentReconciliationFacade facade = new PaymentReconciliationFacade(reconciliationStateService,
                fakePaymentClient, new ReconciliationProperties(
                        Duration.ofMinutes(5), Duration.ofMinutes(1), 500, MAX_ROUND, 3, callsPerSecond));
        fakePaymentClient.willLookup(PgPaymentStatus.DONE, PRICE * QUANTITY);
        fakePaymentClient.delay(pgDelay); // 대상을 다 만든 뒤에 건다 (승인에도 적용되므로)

        // when
        ReconciliationRoundResult result = facade.reconcilePayments();

        // then
        long paid = orders.stream()
                .map(o -> orderRepository.findById(o.getId()).orElseThrow().getStatus())
                .filter(s -> s == OrderStatus.PAID)
                .count();
        double perTargetMs = result.targets() == 0 ? 0 : (double) result.elapsedMs() / result.targets();
        log.info("[측정 {}] PG 지연={}ms, 초당={}, 대상={}, 확정={}, PG 실패={}, 반영 오류={}, 남은 대상 있음={}, "
                        + "소요={}ms, 건당 평균={}ms, PG 조회={}번, PAID={}건",
                code, pgDelay.toMillis(), callsPerSecond, result.targets(), result.applied(), result.pgFailures(),
                result.errors(), result.hasMore(), result.elapsedMs(), String.format("%.1f", perTargetMs),
                fakePaymentClient.lookupCallCount(), paid);

        assertThat(result.errors()).as("반영 오류 없음").isZero();
        assertThat(result.pgFailures()).as("PG 조회 실패 없음").isZero();
        assertThat(result.applied()).as("처리를 시도한 대상은 모두 확정").isEqualTo(result.targets());
        assertThat(paid).as("확정 건수 = PAID 주문 수").isEqualTo(result.applied());
        assertThat(result.hasMore()).as("다 처리하지 못했으면 남은 대상 있음으로 보고")
                .isEqualTo(result.targets() < TARGET_COUNT);
    }

    /** 실제 승인 흐름에 승인 타임아웃을 걸어 PAYMENT_UNKNOWN을 만들고, 상태가 된 시각을 minutesAgo분 전으로 옮긴다. */
    private List<Order> createPaymentUnknownOrders(int count, int minutesAgo) {
        Product product = productRepository.save(Product.create("키보드", PRICE, count * QUANTITY + 100));
        PaymentFacade approveFacade = new PaymentFacade(approveStateService, fakePaymentClient);
        fakePaymentClient.willTimeout();
        List<Order> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
            catchThrowable(() -> approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                    "payment-key-" + order.getId()));
            created.add(order);
        }
        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE status = 'PAYMENT_UNKNOWN'",
                LocalDateTime.now().minusMinutes(minutesAgo));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'PAYMENT_UNKNOWN'", Integer.class))
                .as("준비: 대상 %d건이 모두 PAYMENT_UNKNOWN", count).isEqualTo(count);
        fakePaymentClient.reset();
        return created;
    }
}
