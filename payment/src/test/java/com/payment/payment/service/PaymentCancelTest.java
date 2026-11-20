package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;
import com.payment.payment.facade.PaymentFacade;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 결제 취소(3-5)를 실제 MySQL과 가짜 PG로 검증한다.
 * 승인은 {@link PaymentFacade}로 실제로 거쳐서 PAID 주문을 만든다. 테스트 메서드에 @Transactional을 붙이지 않는다.
 * 선점·반영 트랜잭션이 각각 실제로 커밋돼야 하기 때문이다.
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class PaymentCancelTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelTest.class);
    private static final long PRICE = 10_000L;
    private static final int THREAD_COUNT = 10;

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
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("PAID 주문을 취소하면 PG 취소가 1번 호출되고 주문은 CANCELED, 재고는 주문 수량만큼 복구된다")
    void cancelSuccess() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        PaymentResponse approved = approve(order);
        assertThat(stock(product)).isEqualTo(3);

        // when
        PaymentResponse canceled = cancelFacade.cancel(order.getId(), "단순 변심");

        // then
        assertThat(canceled.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.canceledTransactionIds()).containsExactly(approved.paymentKey());
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .as("취소가 확정되면 결제 시도도 CANCELED가 된다").isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 취소된 주문을 다시 취소하면 실패하고 PG는 다시 호출되지 않으며 재고도 한 번만 복구된다")
    void cancelTwiceFails() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        cancelFacade.cancel(order.getId(), "단순 변심");

        // when // then
        assertThatThrownBy(() -> cancelFacade.cancel(order.getId(), "다시 취소"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("결제되지 않은(PENDING) 주문은 취소할 수 없고 PG도 호출되지 않는다")
    void cancelFailsWhenNotPaid() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);

        // when // then
        assertThatThrownBy(() -> cancelFacade.cancel(order.getId(), "단순 변심"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("PG 취소가 실패하면 주문은 PAID로 돌아가고 재고는 복구되지 않으며, 이후 다시 취소할 수 있다")
    void pgCancelFailsThenRetry() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willFailCancel();

        // when // then
        assertThatThrownBy(() -> cancelFacade.cancel(order.getId(), "단순 변심"))
                .isInstanceOf(PaymentCancelFailedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(product)).isEqualTo(3);

        // when: PG가 복구된 뒤 재시도
        fakePaymentClient.reset();
        PaymentResponse retried = cancelFacade.cancel(order.getId(), "재시도");

        // then
        assertThat(retried.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("품절이 된 상품의 주문을 취소하면 재고가 복구되고 상품은 판매중으로 돌아온다")
    void cancelRestoresSoldOutProduct() {
        // given
        Product product = saveProduct(2);
        Order order = saveOrder(product, 2);
        approve(order);
        assertThat(productStatus(product)).isEqualTo(ProductStatus.SOLD_OUT);

        // when
        cancelFacade.cancel(order.getId(), "단순 변심");

        // then
        assertThat(stock(product)).isEqualTo(2);
        assertThat(productStatus(product)).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @Tag("concurrency")
    @DisplayName("같은 주문을 10번 동시에 취소하면 정확히 1번만 성공하고 PG 취소는 1번만 호출되며 재고는 한 번만 복구된다")
    void concurrentCancelsOfSameOrder() throws InterruptedException {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.delay(Duration.ofMillis(300));

        // when
        long start = System.nanoTime();
        List<Throwable> errors = runConcurrently(THREAD_COUNT,
                index -> () -> cancelFacade.cancel(order.getId(), "동시 취소 " + index));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // then
        long stateConflicts = errors.stream().filter(e -> e instanceof IllegalStateException).count();
        log.info("[비관적 락] 같은 주문 취소 {}건 → 성공 {}, 상태 충돌 {}, PG 취소 호출 {}, 최종 재고 {}, {}ms",
                THREAD_COUNT, THREAD_COUNT - errors.size(), stateConflicts,
                fakePaymentClient.cancelCallCount(), stock(product), elapsedMs);
        assertThat(errors.size()).as("실패는 모두 상태 충돌이어야 한다").isEqualTo((int) stateConflicts);
        assertThat(THREAD_COUNT - errors.size()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @Tag("concurrency")
    @DisplayName("같은 상품에 대해 취소 5건과 승인 5건이 동시에 일어나도 데드락 없이 모두 성공하고 최종 재고가 정확하다")
    void concurrentCancelsAndApprovalsOnSameProduct() throws InterruptedException {
        // given: 재고 10, 이미 결제된 주문 5건(재고 5 남음)과 결제 대기 주문 5건
        Product product = saveProduct(10);
        List<Order> paidOrders = new ArrayList<>();
        List<Order> pendingOrders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Order paid = saveOrder(product, 1);
            approve(paid);
            paidOrders.add(paid);
            pendingOrders.add(saveOrder(product, 1));
        }
        assertThat(stock(product)).isEqualTo(5);
        fakePaymentClient.delay(Duration.ofMillis(100));

        // when
        long start = System.nanoTime();
        List<Throwable> errors = runConcurrently(THREAD_COUNT, index -> index < 5
                ? () -> cancelFacade.cancel(paidOrders.get(index).getId(), "취소")
                : () -> approve(pendingOrders.get(index - 5)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // then: 5 + 취소 5 - 승인 5 = 5
        log.info("[비관적 락] 취소 5 + 승인 5 동시 → 실패 {}, 최종 재고 {}, {}ms", errors.size(), stock(product), elapsedMs);
        assertThat(errors).isEmpty();
        assertThat(stock(product)).isEqualTo(5);
        assertThat(paidOrders).allSatisfy(o -> assertThat(orderStatus(o)).isEqualTo(OrderStatus.CANCELED));
        assertThat(pendingOrders).allSatisfy(o -> assertThat(orderStatus(o)).isEqualTo(OrderStatus.PAID));
    }

    /** 주문마다 다른 멱등키와 paymentKey로 결제한다. (실제로도 결제창 인증마다 paymentKey가 다르다) */
    private PaymentResponse approve(Order order) {
        return approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                "payment-key-" + order.getId());
    }

    /** 모든 스레드를 동시에 출발시켜, 실패한 작업의 예외만 모아 돌려준다. */
    private List<Throwable> runConcurrently(int count, java.util.function.IntFunction<Callable<?>> taskFor)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < count; i++) {
            Callable<?> task = taskFor.apply(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.call();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();
        return List.copyOf(errors);
    }

    private Product saveProduct(int stock) {
        return productRepository.save(Product.create("키보드", PRICE, stock));
    }

    private Order saveOrder(Product product, int quantity) {
        return orderRepository.save(Order.create(100L, product, quantity));
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private ProductStatus productStatus(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStatus();
    }

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
