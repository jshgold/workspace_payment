package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.IdempotencyKeyRepository;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentApprovalFacade;
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

/**
 * 멱등키와 중복 결제 방지를 실제 MySQL과 가짜 PG로 검증하는 공통 틀. (3-4)
 * 상태 선점 방식이 다른 {@link PaymentStateService} 구현체는 {@link #stateService()}만 바꿔 같은 시나리오로 비교한다.
 * <p>
 * 동시성 테스트에서는 가짜 PG에 지연을 줘서 "첫 요청이 PG 응답을 기다리는 사이에 나머지가 도착하는" 상황을 만든다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다. 키 선점·반영 트랜잭션이 각각 실제로 커밋돼야 하기 때문이다.
 */
public abstract class PaymentApprovalTestSupport extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalTestSupport.class);
    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;
    private static final int THREAD_COUNT = 10;
    private static final Duration PG_DELAY = Duration.ofMillis(300);

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    protected IdempotencyService idempotencyService;

    @Autowired
    protected FakePaymentClient fakePaymentClient;

    protected PaymentApprovalFacade approvalFacade;

    protected abstract PaymentStateService stateService();

    protected abstract String strategyName();

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        approvalFacade = new PaymentApprovalFacade(
                idempotencyService, new PaymentFacade(stateService(), fakePaymentClient));
    }

    @AfterEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("같은 키로 순차 재요청하면 PG를 다시 부르지 않고 첫 성공 결과를 그대로 돌려준다")
    void replayReturnsFirstResult() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);

        // when
        PaymentResponse first = approvalFacade.approve(order.getId(), "key-1", AMOUNT);
        PaymentResponse second = approvalFacade.approve(order.getId(), "key-1", AMOUNT);

        // then
        assertThat(second).isEqualTo(first);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(5 - QUANTITY);
    }

    @Test
    @DisplayName("PG가 거절한 요청을 같은 키로 재요청하면 PG를 다시 부르지 않고 같은 거절을 돌려준다")
    void replayOfDeclinedReturnsSameFailure() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.willDecline("한도 초과");
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT))
                .isInstanceOf(PaymentDeclinedException.class);

        // when // then
        fakePaymentClient.willApprove();
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED));
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 타임아웃은 결과를 모르므로 같은 키의 재요청이 실패가 아니라 '처리 중'을 받고 PG를 다시 부르지 않는다")
    void timeoutThenRetryGetsInProgress() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.willTimeout();
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT))
                .isInstanceOf(PaymentTimeoutException.class);

        // when // then
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    @DisplayName("다른 주문에 쓴 키를 재사용하면 거절하고 PG를 부르지 않는다")
    void keyReusedForAnotherOrderIsRejected() {
        // given
        Product product = saveProduct(10);
        Order first = saveOrder(product);
        Order second = saveOrder(product);
        approvalFacade.approve(first.getId(), "key-1", AMOUNT);

        // when // then
        assertThatThrownBy(() -> approvalFacade.approve(second.getId(), "key-1", AMOUNT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(orderStatus(second)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("요청 금액이 서버가 계산한 주문 금액과 다르면 PG를 부르지 않고 키도 남기지 않는다")
    void requestAmountMismatch() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);

        // when // then
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", 100L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_REQUEST_AMOUNT_MISMATCH));
        assertThat(fakePaymentClient.approveCallCount()).isZero();
        assertThat(idempotencyKeyRepository.count()).isZero();
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @Tag("concurrency")
    @DisplayName("같은 주문에 같은 키로 10번 동시에 요청하면 PG는 1번만 호출되고 결제 기록은 1개이며 재고는 한 번만 차감된다")
    void concurrentRequestsWithSameKey() throws InterruptedException {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.delay(PG_DELAY);

        // when
        List<Outcome> outcomes = runConcurrently(order.getId(), index -> "same-key");

        // then
        long succeeded = outcomes.stream().filter(Outcome::isSuccess).count();
        long inProgress = outcomes.stream().filter(o -> o.hasErrorCode(ErrorCode.PAYMENT_IN_PROGRESS)).count();
        log.info("[{}] 같은 키 {}건 → 성공 {}, 처리 중 {}, 기타 {}, PG 호출 {}",
                strategyName(), THREAD_COUNT, succeeded, inProgress,
                THREAD_COUNT - succeeded - inProgress, fakePaymentClient.approveCallCount());
        assertThat(succeeded + inProgress).as("성공 또는 '처리 중' 외의 결과는 없어야 한다").isEqualTo(THREAD_COUNT);
        assertThat(succeeded).isGreaterThanOrEqualTo(1);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(5 - QUANTITY);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    @Tag("concurrency")
    @DisplayName("같은 주문에 서로 다른 키로 10번 동시에 요청하면 정확히 1번만 성공하고 PG도 1번만 호출된다")
    void concurrentRequestsWithDifferentKeys() throws InterruptedException {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.delay(PG_DELAY);

        // when
        List<Outcome> outcomes = runConcurrently(order.getId(), index -> "key-" + index);

        // then
        long succeeded = outcomes.stream().filter(Outcome::isSuccess).count();
        long stateConflicts = outcomes.stream()
                .filter(o -> o.error() instanceof IllegalStateException).count();
        log.info("[{}] 다른 키 {}건 → 성공 {}, 상태 충돌 {}, 기타 {}, PG 호출 {}",
                strategyName(), THREAD_COUNT, succeeded, stateConflicts,
                THREAD_COUNT - succeeded - stateConflicts, fakePaymentClient.approveCallCount());
        assertThat(succeeded).isEqualTo(1);
        assertThat(stateConflicts).isEqualTo(THREAD_COUNT - 1);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(5 - QUANTITY);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
    }

    private List<Outcome> runConcurrently(Long orderId, IntFunction<String> keyFor) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        ConcurrentLinkedQueue<Outcome> outcomes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            String key = keyFor.apply(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    outcomes.add(new Outcome(approvalFacade.approve(orderId, key, AMOUNT), null));
                } catch (Throwable e) {
                    outcomes.add(new Outcome(null, e));
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();
        return List.copyOf(outcomes);
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

    private record Outcome(PaymentResponse response, Throwable error) {

        boolean isSuccess() {
            return error == null;
        }

        boolean hasErrorCode(ErrorCode errorCode) {
            return error instanceof BusinessException e && e.getErrorCode() == errorCode;
        }
    }
}
