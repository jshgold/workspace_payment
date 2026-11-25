package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.order.service.OrderExpirationService;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgPaymentStatus;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PessimisticLockPaymentStateService;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * phase-04 4-4 완료 조건 중 "여러 경로가 같은 주문을 바꿔도 한 번만 반영된다"의 재현 테스트.
 * <b>구현 후의 올바른 동작</b>을 기대한다. 구현 전에는 진입점이 없어 실패한다.
 */
@Slf4j
@Tag("concurrency")
class ReconciliationConcurrencyTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 2;

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
    private PaymentReconciliationFacade reconciliationFacade;

    @Autowired
    private OrderExpirationService expirationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentFacade approveFacade;

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        approveFacade = new PaymentFacade(approveStateService, fakePaymentClient);
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("X1 — 상품 1 × 재고 100, 31분 지난 PENDING 주문 20개(수량 2)에 만료 1번과 승인 요청 20건이 동시에 들어옴 → 각 주문은 EXPIRED(결제 기록·PG 승인 없음) 또는 PAID 중 하나만 되고, 재고 = 100 - 2 × PAID 건수, PG 승인 횟수 = PAID 건수")
    void expirationAndPaymentStartAreExclusive() throws InterruptedException {
        // given
        int initialStock = 100;
        int orderCount = 20;
        Product product = productRepository.save(Product.create("키보드", PRICE, initialStock));
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            orders.add(saveAgedPendingOrder(product, 31));
        }

        ExecutorService executor = Executors.newFixedThreadPool(orderCount + 1);
        CountDownLatch ready = new CountDownLatch(orderCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(orderCount + 1);
        Queue<Throwable> expireErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> approveErrors = new ConcurrentLinkedQueue<>();

        // when
        long startedAt = System.nanoTime();
        executor.submit(() -> {
            ready.countDown();
            await(start);
            try {
                expirationService.expire();
            } catch (Throwable e) {
                expireErrors.add(e);
            } finally {
                done.countDown();
            }
        });
        for (Order order : orders) {
            executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                            "payment-key-" + order.getId());
                } catch (Throwable e) {
                    approveErrors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        log.info("[X1] 만료 + 승인 {}건 동시 실행 소요 {}ms", orderCount, (System.nanoTime() - startedAt) / 1_000_000);

        // then
        assertThat(expireErrors).as("만료 작업은 예외 없이 끝나야 한다").isEmpty();
        assertThat(approveErrors).as("거절된 승인 요청은 만료된 주문에 대한 ORDER_NOT_PAYABLE뿐이어야 한다")
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(BusinessException.class,
                        be -> assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE)));

        int paid = 0;
        int expired = 0;
        for (Order order : orders) {
            OrderStatus status = orderRepository.findById(order.getId()).orElseThrow().getStatus();
            if (status == OrderStatus.PAID) {
                paid++;
                assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.APPROVED);
            } else if (status == OrderStatus.EXPIRED) {
                expired++;
                assertThat(paymentRepository.findByOrderId(order.getId()))
                        .as("만료가 이긴 주문에는 결제 시도가 없어야 한다").isEmpty();
            }
        }
        log.info("[X1] PAID {}건, EXPIRED {}건", paid, expired);
        assertThat(paid + expired).as("모든 주문이 EXPIRED 또는 PAID 중 하나").isEqualTo(orderCount);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(paid);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(initialStock - QUANTITY * paid);
    }

    @Test
    @DisplayName("X2 — 상품 1 × 재고 5, PAYMENT_UNKNOWN 6분 경과 주문 1개(수량 2)에 승인 대사 2개가 동시에 돎, PG 조회 ABORTED(300ms 지연) → 주문 FAILED, 결제 FAILED가 한 번만 반영되고 재고는 한 번만 복구되어 5, 두 대사 모두 예외 없음")
    void concurrentReconcilersApplyOnce() throws InterruptedException {
        // given
        Product product = productRepository.save(Product.create("키보드", PRICE, 5));
        Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
        fakePaymentClient.willTimeout();
        catchThrowable(() -> approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                "payment-key-" + order.getId()));
        jdbcTemplate.update("UPDATE orders SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(6), order.getId());
        fakePaymentClient.reset();
        fakePaymentClient.willLookup(PgPaymentStatus.ABORTED, order.getTotalPrice());
        fakePaymentClient.delay(Duration.ofMillis(300)); // 두 대사가 조회 구간에서 겹치게 한다

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // when
        long startedAt = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    reconciliationFacade.reconcilePayments();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        log.info("[X2] 대사 {}개 동시 실행 소요 {}ms, PG 조회 {}번",
                threads, (System.nanoTime() - startedAt) / 1_000_000, fakePaymentClient.lookupCallCount());

        // then
        assertThat(errors).as("늦게 온 대사는 상태가 이미 바뀐 것을 보고 건너뛴다").isEmpty();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .as("재고 복구가 두 번 일어나면 7이 된다").isEqualTo(5);
    }

    /** 시각은 JVM 기준으로 넣는다. (엔티티의 @CreationTimestamp, @UpdateTimestamp와 같은 시계) */
    private Order saveAgedPendingOrder(Product product, int minutesAgo) {
        Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
        LocalDateTime at = LocalDateTime.now().minusMinutes(minutesAgo);
        jdbcTemplate.update("UPDATE orders SET created_at = ?, updated_at = ? WHERE id = ?", at, at, order.getId());
        return order;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
