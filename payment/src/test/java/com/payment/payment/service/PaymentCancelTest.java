package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancel;
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentCancelRepository;
import com.payment.payment.domain.PaymentCancelStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
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
 * 결제 취소(3-5, 4-3)를 실제 MySQL과 가짜 PG로 검증한다.
 * 승인은 {@link PaymentFacade}로 실제로 거쳐서 PAID 주문을 만든다. 테스트 메서드에 @Transactional을 붙이지 않는다.
 * 선점·반영 트랜잭션이 각각 실제로 커밋돼야 하기 때문이다.
 * <p>
 * 외부 연동 경우(test-convention 4절)마다 주문·결제·취소 시도·재고, 같은 키 재전송의 응답과 PG 호출 횟수를 본다.
 * "PG 성공 후 결과 반영 실패"는 {@code PaymentCancelFacadeTest}에서 본다. (반영 트랜잭션만 골라 실패시키기 위해)
 * <p>
 * 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (승인 후 재고 3)
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class PaymentCancelTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelTest.class);
    private static final long PRICE = 10_000L;
    private static final int THREAD_COUNT = 10;
    private static final String REASON = "단순 변심";

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentCancelRepository paymentCancelRepository;

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
        paymentCancelRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    // ---- 1. 성공 ----

    @Test
    @DisplayName("성공: PAID 주문을 취소하면 PG 취소 1번, 주문·결제 CANCELED, 취소 시도 COMPLETED, 재고는 주문 수량만큼 복구된다")
    void cancelSuccess() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        PaymentResponse approved = approve(order);
        assertThat(stock(product)).isEqualTo(3);

        // when
        PaymentResponse canceled = cancelFacade.cancel(order.getId(), "k1", REASON);

        // then
        assertThat(canceled.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(canceled.paymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.CANCELED);
        assertThat(attempts(order)).extracting(PaymentCancel::getStatus).containsExactly(PaymentCancelStatus.COMPLETED);
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.canceledTransactionIds()).containsExactly(approved.paymentKey());
    }

    @Test
    @DisplayName("성공 후 같은 키로 재전송하면 같은 결과를 받고, PG는 다시 호출되지 않으며 재고도 한 번만 복구된다")
    void resendAfterSuccessIsReplayed() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        PaymentResponse first = cancelFacade.cancel(order.getId(), "k1", REASON);

        // when
        PaymentResponse resent = cancelFacade.cancel(order.getId(), "k1", REASON);

        // then
        assertThat(resent).isEqualTo(first);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(attempts(order)).hasSize(1);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("성공: PG가 첫 시도에 '이미 취소된 결제'라고 답해도 취소 성공으로 보고 CANCELED, 재고 복구한다")
    void alreadyCanceledByPgIsSuccess() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willAlreadyCanceled();

        // when
        PaymentResponse canceled = cancelFacade.cancel(order.getId(), "k1", REASON);

        // then
        assertThat(canceled.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.CANCELED);
        assertThat(attempts(order)).extracting(PaymentCancel::getStatus).containsExactly(PaymentCancelStatus.COMPLETED);
        assertThat(stock(product)).isEqualTo(5);
    }

    // ---- 2. 명확한 거절 ----

    @Test
    @DisplayName("거절: PG가 취소를 거절하면 취소 시도 FAILED(PAYMENT_CANCEL_DECLINED), 주문 PAID, 결제 APPROVED, 재고는 그대로이고 같은 키 재전송은 같은 거절을 받으며 PG를 다시 부르지 않는다")
    void declined() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willFailCancel();

        // when
        Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(thrown).isInstanceOf(PaymentCancelDeclinedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.APPROVED);
        assertFailedAttempt(order, ErrorCode.PAYMENT_CANCEL_DECLINED);
        assertThat(stock(product)).isEqualTo(3);

        // when: 같은 키 재전송
        Throwable resent = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(errorCode(resent)).isEqualTo(ErrorCode.PAYMENT_CANCEL_DECLINED);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("거절 후 새 키로 다시 취소하면 새 시도로 PG를 다시 호출하고, 성공하면 시도 기록은 FAILED, COMPLETED 두 건이 남는다")
    void newKeyAfterDeclineIsNewAttempt() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willFailCancel();
        catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));
        fakePaymentClient.reset();

        // when
        PaymentResponse retried = cancelFacade.cancel(order.getId(), "k2", "재시도");

        // then
        assertThat(retried.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(attempts(order)).extracting(PaymentCancel::getStatus)
                .containsExactly(PaymentCancelStatus.FAILED, PaymentCancelStatus.COMPLETED);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(5);
    }

    // ---- 3. 요청이 PG에 닿지 않음 ----

    @Test
    @DisplayName("연결 실패: 취소 요청이 PG에 닿지 않으면 취소 시도 FAILED(PAYMENT_CANCEL_GATEWAY_ERROR), 주문 PAID, 재고 그대로이고 같은 키 재전송은 같은 실패를 받는다")
    void connectionFailed() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willFailCancelConnection();

        // when
        Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(errorCode(thrown)).isEqualTo(ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.APPROVED);
        assertFailedAttempt(order, ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
        assertThat(stock(product)).isEqualTo(3);

        // when: 같은 키 재전송
        Throwable resent = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(errorCode(resent)).isEqualTo(ErrorCode.PAYMENT_CANCEL_GATEWAY_ERROR);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
    }

    // ---- 4. 결과 모름 ----

    @Test
    @DisplayName("결과 모름: PG 취소가 타임아웃되면 취소 시도 UNKNOWN, 주문 CANCEL_UNKNOWN, 결제 APPROVED, 재고는 복구하지 않고, 같은 키·새 키 재요청 모두 '처리 중'을 받으며 PG를 다시 부르지 않는다")
    void timeoutBecomesCancelUnknown() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willTimeoutCancel();

        // when
        Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(errorCode(thrown)).isEqualTo(ErrorCode.PAYMENT_CANCEL_UNKNOWN);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCEL_UNKNOWN);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(attempts(order)).extracting(PaymentCancel::getStatus).containsExactly(PaymentCancelStatus.UNKNOWN);
        assertThat(stock(product)).isEqualTo(3);

        // when: 같은 키 재전송, 새 키 요청
        Throwable sameKey = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));
        Throwable newKey = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k2", REASON));

        // then
        assertThat(errorCode(sameKey)).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        assertThat(errorCode(newKey)).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        assertThat(fakePaymentClient.cancelCallCount())
                .as("첫 호출 + 같은 PG 멱등키로 재시도 1번(4-5). 같은 키·새 키 재요청은 PG를 부르지 않는다")
                .isEqualTo(2);
        assertThat(attempts(order)).as("새 키 요청은 시도가 아니므로 기록하지 않는다").hasSize(1);
    }

    // ---- 멱등키·상태 판정 ----

    @Test
    @DisplayName("같은 키로 사유나 주문이 다른 요청이 오면 저장된 결과를 돌려주지 않고 IDEMPOTENCY_KEY_REUSED로 거절하며 PG를 부르지 않는다")
    void sameKeyDifferentContentIsRejected() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        Order otherOrder = saveOrder(product, 1);
        approve(order);
        approve(otherOrder);
        cancelFacade.cancel(order.getId(), "k1", REASON);

        // when
        Throwable differentReason = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", "배송 지연"));
        Throwable differentOrder = catchThrowable(() -> cancelFacade.cancel(otherOrder.getId(), "k1", REASON));

        // then
        assertThat(errorCode(differentReason)).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(errorCode(differentOrder)).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(orderStatus(otherOrder)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("이미 취소된 주문에 새 키로 취소하면 ORDER_ALREADY_CANCELED이고, PG는 다시 호출되지 않으며 시도도 기록하지 않는다")
    void newKeyOnCanceledOrder() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        cancelFacade.cancel(order.getId(), "k1", REASON);

        // when
        Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k2", REASON));

        // then
        assertThat(errorCode(thrown)).isEqualTo(ErrorCode.ORDER_ALREADY_CANCELED);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(attempts(order)).hasSize(1);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("결제되지 않은(PENDING) 주문은 ORDER_NOT_CANCELABLE이고 PG도 호출되지 않으며 시도도 기록하지 않는다")
    void cancelFailsWhenNotPaid() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);

        // when
        Throwable thrown = catchThrowable(() -> cancelFacade.cancel(order.getId(), "k1", REASON));

        // then
        assertThat(errorCode(thrown)).isEqualTo(ErrorCode.ORDER_NOT_CANCELABLE);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PENDING);
        assertThat(attempts(order)).isEmpty();
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("멱등키나 사유가 비어 있으면 트랜잭션 전에 거절하고 PG를 부르지 않는다")
    void blankInputIsRejected() {
        // when // then
        assertThatThrownBy(() -> cancelFacade.cancel(1L, " ", REASON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancelFacade.cancel(1L, "k1", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
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
        cancelFacade.cancel(order.getId(), "k1", REASON);

        // then
        assertThat(stock(product)).isEqualTo(2);
        assertThat(productStatus(product)).isEqualTo(ProductStatus.ON_SALE);
    }

    // ---- 동시성 ----

    @Test
    @Tag("concurrency")
    @DisplayName("같은 주문을 서로 다른 키로 10번 동시에 취소하면 1번만 성공하고 PG 취소는 1번, 취소 시도 기록 1건, 재고는 한 번만 복구된다")
    void concurrentCancelsOfSameOrder() throws InterruptedException {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.delay(Duration.ofMillis(300));

        // when
        long start = System.nanoTime();
        List<Throwable> errors = runConcurrently(THREAD_COUNT,
                index -> () -> cancelFacade.cancel(order.getId(), "k" + index, REASON));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // then
        log.info("[취소] 다른 키 {}건 동시 → 성공 {}, 실패 {}, PG 취소 호출 {}, 최종 재고 {}, {}ms",
                THREAD_COUNT, THREAD_COUNT - errors.size(), errors.size(),
                fakePaymentClient.cancelCallCount(), stock(product), elapsedMs);
        assertThat(errors).as("진 요청은 '처리 중' 또는 '이미 취소됨'만 받아야 한다")
                .allSatisfy(e -> assertThat(errorCode(e)).isIn(ErrorCode.PAYMENT_IN_PROGRESS, ErrorCode.ORDER_ALREADY_CANCELED));
        assertThat(THREAD_COUNT - errors.size()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(attempts(order)).hasSize(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @Tag("concurrency")
    @DisplayName("같은 주문을 같은 키로 10번 동시에 취소해도 PG 취소는 1번, 취소 시도 기록 1건이고, 나머지는 '처리 중' 또는 같은 결과를 받는다")
    void concurrentCancelsWithSameKey() throws InterruptedException {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.delay(Duration.ofMillis(300));

        // when
        long start = System.nanoTime();
        List<Throwable> errors = runConcurrently(THREAD_COUNT, index -> () -> cancelFacade.cancel(order.getId(), "k1", REASON));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // then
        log.info("[취소] 같은 키 {}건 동시 → 성공(첫 요청 + replay) {}, 처리 중 {}, PG 취소 호출 {}, 최종 재고 {}, {}ms",
                THREAD_COUNT, THREAD_COUNT - errors.size(), errors.size(),
                fakePaymentClient.cancelCallCount(), stock(product), elapsedMs);
        assertThat(errors).allSatisfy(e -> assertThat(errorCode(e)).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
        assertThat(THREAD_COUNT - errors.size()).isGreaterThanOrEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(attempts(order)).hasSize(1);
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
                ? () -> cancelFacade.cancel(paidOrders.get(index).getId(), "cancel-" + index, "취소")
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

    private void assertFailedAttempt(Order order, ErrorCode failureCode) {
        List<PaymentCancel> attempts = attempts(order);
        assertThat(attempts).as("실패한 취소 시도도 기록으로 남아야 한다").hasSize(1);
        assertThat(attempts.getFirst().getStatus()).isEqualTo(PaymentCancelStatus.FAILED);
        assertThat(attempts.getFirst().getFailureCode()).isEqualTo(failureCode.name());
    }

    private static ErrorCode errorCode(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).getErrorCode();
    }

    private List<PaymentCancel> attempts(Order order) {
        return paymentCancelRepository.findAllByOrderIdOrderByIdAsc(order.getId());
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

    private PaymentStatus paymentStatus(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus();
    }
}
