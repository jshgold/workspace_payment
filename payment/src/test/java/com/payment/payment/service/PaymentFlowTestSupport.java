package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentFacade;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ClassUtils;

/**
 * 결제 승인 시나리오(정상, PG 실패, DB 실패 후 보상)를 실제 MySQL과 가짜 PG로 검증하는 공통 틀.
 * 동시성 제어 방식이 다른 {@link PaymentStateService} 구현체는 {@link #stateService()}만 바꿔서 같은 시나리오로 비교한다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다. 선점·반영 트랜잭션이 각각 실제로 커밋돼야 하기 때문이다.
 */
public abstract class PaymentFlowTestSupport extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PaymentFlowTestSupport.class);

    private static final long PRICE = 10_000L;
    private static final int CONTENDING_ORDERS = 10;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected FakePaymentClient fakePaymentClient;

    protected PaymentFacade paymentFacade;

    protected abstract PaymentStateService stateService();

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        paymentFacade = new PaymentFacade(stateService(), fakePaymentClient);
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("정상 승인이면 주문은 PAID, 결제 기록이 저장되고 재고가 주문 수량만큼 차감된다")
    void approveSuccess() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);

        // when
        PaymentResponse response = paymentFacade.approve(order.getId(), "payment-key-1");

        // then
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(response.amount()).isEqualTo(2 * PRICE);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByOrderId(order.getId())).isPresent();
        assertThat(stock(product)).isEqualTo(3);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("PG가 거절하면 주문은 PENDING으로 돌아가고 결제 기록과 재고 변화는 없다")
    void pgDeclined() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willDecline("한도 초과");

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(order.getId(), "payment-key-1")).isInstanceOf(PaymentDeclinedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentRepository.count()).isZero();
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("PG가 타임아웃이면 승인 여부를 모르므로 주문은 PAYING으로 남고 취소 호출도 없다")
    void pgTimeout() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willTimeout();

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(order.getId(), "payment-key-1")).isInstanceOf(PaymentTimeoutException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYING);
        assertThat(paymentRepository.count()).isZero();
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("승인 후 재고 부족으로 DB 반영이 실패하면 트랜잭션이 롤백되고 PG 승인이 취소되며 주문은 PENDING으로 돌아간다")
    void dbFailureThenCompensate() {
        // given: 주문 생성 이후 다른 주문이 재고를 가져가 결제 시점에는 재고가 모자란 상황
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        setStock(product, 1);

        // when // then
        // 구현체마다 예외 타입은 다르다. (비관적 락: 도메인의 IllegalStateException, 원자적 쿼리: BusinessException)
        assertThatThrownBy(() -> paymentFacade.approve(order.getId(), "payment-key-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("재고가 부족");
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentRepository.count()).isZero();
        assertThat(stock(product)).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 반영 실패 후 보상 취소마저 실패하면 주문은 PAYING으로 남아 추적할 수 있다")
    void compensationFails() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        setStock(product, 1);
        fakePaymentClient.willFailCancel();

        // when // then
        assertThatThrownBy(() -> paymentFacade.approve(order.getId(), "payment-key-1"))
                .isInstanceOf(PaymentCancelFailedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYING);
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 PAID인 주문의 결과 반영이 한 번 더 와도 결제 기록은 하나이고 재고는 한 번만 차감된다")
    void duplicateCompleteIsIgnored() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        stateService().startPaying(order.getId());
        PgApproveResult approved = new PgApproveResult("pg-tx-dup", 2 * PRICE);

        // when
        PaymentResponse first = stateService().complete(order.getId(), approved);
        PaymentResponse second = stateService().complete(order.getId(), approved);

        // then
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 결제가 진행 중인 주문은 다시 선점할 수 없다")
    void startPayingTwiceFails() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 1);
        stateService().startPaying(order.getId());

        // when // then
        assertThatThrownBy(() -> stateService().startPaying(order.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * [트러블슈팅 재현] docs/troubleshooting/payment-stock-deduction-timing.md
     * 재고를 PG 승인 뒤에 차감하므로, 재고 경합에서 질 주문도 전부 PG 승인까지 가고 그 뒤 보상 취소된다.
     * 이 테스트는 <b>현재 구조의 문제를 고정</b>한다. 재고 차감을 PG 호출 앞으로 옮기면 PG 호출 횟수 단언이 깨지는 것이 개선의 증거다.
     */
    @Test
    @Tag("concurrency")
    @DisplayName("재고 1개 상품에 서로 다른 주문 10건이 동시에 결제하면 1건만 성공하지만, PG 승인은 10번 모두 일어나고 진 9건은 승인 후 취소된다")
    void stockContentionCausesApproveThenCancel() throws InterruptedException {
        // given
        int initialStock = 1;
        Product product = saveProduct(initialStock);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < CONTENDING_ORDERS; i++) {
            orders.add(saveOrder(product, 1));
        }

        // when
        long startedAt = System.currentTimeMillis();
        List<Throwable> failures = approveConcurrently(orders);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        // then
        long succeeded = CONTENDING_ORDERS - failures.size();
        long stockShortages = failures.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("재고가 부족"))
                .count();
        log.info("[{}] 재고 {} / 주문 {}건 동시 결제 → 성공 {}, 재고 부족 {}, 기타 실패 {}, PG 승인 {}회, PG 취소 {}회, 소요 {}ms",
                ClassUtils.getUserClass(stateService()).getSimpleName(), initialStock, CONTENDING_ORDERS,
                succeeded, stockShortages, failures.size() - stockShortages,
                fakePaymentClient.approveCallCount(), fakePaymentClient.cancelCallCount(), elapsedMs);

        assertThat(succeeded).isEqualTo(1);
        assertThat(stockShortages).as("실패는 모두 재고 부족이어야 한다").isEqualTo(CONTENDING_ORDERS - 1);
        assertThat(succeeded * 1 + stock(product)).isEqualTo(initialStock);
        assertThat(paymentRepository.count()).isEqualTo(1);

        // 문제의 증거: 진 주문도 PG 승인까지 갔고, 그만큼 보상 취소가 일어났다.
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(CONTENDING_ORDERS);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(CONTENDING_ORDERS - 1);

        // 진 주문은 승인 → 취소를 거쳐 PENDING으로 돌아왔다. (재고 0인데도 다시 결제를 시도할 수 있다)
        long paid = orders.stream().filter(o -> orderStatus(o) == OrderStatus.PAID).count();
        long pending = orders.stream().filter(o -> orderStatus(o) == OrderStatus.PENDING).count();
        assertThat(paid).isEqualTo(1);
        assertThat(pending).isEqualTo(CONTENDING_ORDERS - 1);
    }

    private List<Throwable> approveConcurrently(List<Order> orders) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(orders.size());
        CountDownLatch ready = new CountDownLatch(orders.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(orders.size());
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < orders.size(); i++) {
            Long orderId = orders.get(i).getId();
            String paymentKey = "payment-key-" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentFacade.approve(orderId, paymentKey);
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();
        return List.copyOf(failures);
    }

    private Product saveProduct(int stock) {
        return productRepository.save(Product.create("키보드", PRICE, stock));
    }

    private Order saveOrder(Product product, int quantity) {
        return orderRepository.save(Order.create(100L, product, quantity));
    }

    private void setStock(Product product, int stock) {
        Product found = productRepository.findById(product.getId()).orElseThrow();
        found.update(found.getName(), found.getPrice(), stock);
        productRepository.save(found);
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
