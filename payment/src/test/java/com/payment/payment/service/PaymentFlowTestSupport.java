package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentStatus;
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
        PaymentResponse response = approve(order, "payment-key-1");

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
    @DisplayName("PG가 거절하면 주문과 결제 시도는 FAILED(PAYMENT_DECLINED)로 끝나고, 선점 때 차감한 재고는 복구된다")
    void pgDeclined() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willDecline("한도 초과");

        // when // then
        assertThatThrownBy(() -> approve(order, "payment-key-1")).isInstanceOf(PaymentDeclinedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
        assertPayment(order, PaymentStatus.FAILED, ErrorCode.PAYMENT_DECLINED);
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("PG가 타임아웃이면 승인 여부를 모르므로 주문은 PAYMENT_UNKNOWN, 결제 시도는 UNKNOWN이 되고, 재고는 쥔 채로 남으며 취소 호출은 없다")
    void pgTimeout() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willTimeout();

        // when // then
        assertThatThrownBy(() -> approve(order, "payment-key-1")).isInstanceOf(PaymentTimeoutException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        assertPayment(order, PaymentStatus.UNKNOWN, null);
        // 개선 A의 비용: 결과를 모르는 주문이 재고를 붙잡는다. 정리는 결제 조회·대사(4-4)의 몫이다.
        assertThat(stock(product)).isEqualTo(3);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("결제 시점에 재고가 모자라면 PG를 부르지 않고, 주문과 결제 시도를 FAILED(STOCK_NOT_AVAILABLE)로 기록하며 재고는 변하지 않는다")
    void stockShortageFailsBeforePg() {
        // given: 주문 생성 이후 다른 주문이 재고를 가져가 결제 시점에는 재고가 모자란 상황
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        setStock(product, 1);

        // when // then
        assertThatThrownBy(() -> approve(order, "payment-key-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_AVAILABLE));
        assertThat(fakePaymentClient.approveCallCount()).isZero();
        assertThat(fakePaymentClient.cancelCallCount()).isZero();
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
        assertPayment(order, PaymentStatus.FAILED, ErrorCode.STOCK_NOT_AVAILABLE);
        assertThat(stock(product)).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 승인 후 금액 불일치로 보상 취소하면 PG 승인이 취소되고, 주문과 결제 시도는 FAILED(승인 금액 기록)로 끝나며 재고는 복구된다")
    void amountMismatchThenCompensate() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willApproveWithAmount(1L);

        // when // then
        assertThatThrownBy(() -> approve(order, "payment-key-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
        Payment payment = assertPayment(order, PaymentStatus.FAILED, ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        assertThat(payment.getApprovedAmount()).isEqualTo(1L);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("PG 승인 후 보상 취소마저 실패하면 돈이 묶였는지 모르므로 PAYMENT_UNKNOWN(PAYMENT_CANCEL_FAILED)으로 남아 대사 대상이 되고, 재고도 복구하지 않는다")
    void compensationFails() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        fakePaymentClient.willApproveWithAmount(1L);
        fakePaymentClient.willFailCancel();

        // when // then
        assertThatThrownBy(() -> approve(order, "payment-key-1"))
                .isInstanceOf(PaymentCancelDeclinedException.class);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        Payment payment = assertPayment(order, PaymentStatus.UNKNOWN, ErrorCode.PAYMENT_CANCEL_FAILED);
        assertThat(payment.getApprovedAmount()).isEqualTo(1L);
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 PAID인 주문의 결과 반영이 한 번 더 와도 결제 기록은 하나이고 재고는 한 번만 차감된다")
    void duplicateCompleteIsIgnored() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        stateService().startPaying(command(order, "key-1", "payment-key-1"));
        PgApproveResult approved = new PgApproveResult("payment-key-1", 2 * PRICE);

        // when
        PaymentResponse first = stateService().complete(order.getId(), approved);
        PaymentResponse second = stateService().complete(order.getId(), approved);

        // then
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 결제가 진행 중인 주문에 새 키로 결제를 시작하면 '처리 중'으로 거절한다")
    void startPayingTwiceFails() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 1);
        stateService().startPaying(command(order, "key-1", "payment-key-1"));

        // when // then
        assertThatThrownBy(() -> stateService().startPaying(command(order, "key-2", "payment-key-2")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
    }

    /**
     * [트러블슈팅 개선 A 검증] docs/troubleshooting/payment-stock-deduction-timing.md
     * 개선 전: 재고를 PG 승인 뒤에 차감해서, 진 주문도 전부 PG 승인까지 가고 그 뒤 보상 취소됐다. (PG 승인 N, 취소 N-1)
     * 개선 후: 재고를 선점(startPaying)에서 차감하므로, 진 주문은 PG를 부르기 전에 실패한다. (PG 승인 1, 취소 0)
     * 4-2+ 후: 진 주문도 결제 시도이므로 주문 FAILED, Payment FAILED(STOCK_NOT_AVAILABLE)로 남는다.
     */
    @Test
    @Tag("concurrency")
    @DisplayName("재고 1개 상품에 서로 다른 주문 10건이 동시에 결제하면 1건만 성공하고, PG 승인은 이긴 1건만 일어나며 취소는 없다")
    void stockContentionApprovesOnlyWinner() throws InterruptedException {
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
                .filter(e -> e instanceof BusinessException be && be.getErrorCode() == ErrorCode.STOCK_NOT_AVAILABLE)
                .count();
        log.info("[{}] 재고 {} / 주문 {}건 동시 결제 → 성공 {}, 재고 부족 {}, 기타 실패 {}, PG 승인 {}회, PG 취소 {}회, 소요 {}ms",
                ClassUtils.getUserClass(stateService()).getSimpleName(), initialStock, CONTENDING_ORDERS,
                succeeded, stockShortages, failures.size() - stockShortages,
                fakePaymentClient.approveCallCount(), fakePaymentClient.cancelCallCount(), elapsedMs);

        assertThat(succeeded).isEqualTo(1);
        assertThat(stockShortages).as("실패는 모두 재고 부족이어야 한다").isEqualTo(CONTENDING_ORDERS - 1);
        assertThat(succeeded * 1 + stock(product)).isEqualTo(initialStock);
        assertThat(paymentRepository.count()).as("진 주문도 실패 시도로 기록된다").isEqualTo(CONTENDING_ORDERS);

        // 개선의 증거: 진 주문은 PG까지 가지 않았다. (개선 전: 승인 10, 취소 9)
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(fakePaymentClient.cancelCallCount()).isZero();

        // 진 주문은 PG 호출 전에 FAILED로 끝난다. 다시 사려면 새 주문을 만든다.
        long paid = orders.stream().filter(o -> orderStatus(o) == OrderStatus.PAID).count();
        long failed = orders.stream().filter(o -> orderStatus(o) == OrderStatus.FAILED).count();
        assertThat(paid).isEqualTo(1);
        assertThat(failed).isEqualTo(CONTENDING_ORDERS - 1);
    }

    private List<Throwable> approveConcurrently(List<Order> orders) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(orders.size());
        CountDownLatch ready = new CountDownLatch(orders.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(orders.size());
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            String paymentKey = "payment-key-" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    approve(order, paymentKey);
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

    private PaymentResponse approve(Order order, String paymentKey) {
        return paymentFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(), paymentKey);
    }

    private StartPayingCommand command(Order order, String idempotencyKey, String paymentKey) {
        return new StartPayingCommand(order.getId(), idempotencyKey, order.getTotalPrice(), paymentKey);
    }

    private Payment assertPayment(Order order, PaymentStatus status, ErrorCode failureCode) {
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getFailureCode()).isEqualTo(failureCode == null ? null : failureCode.name());
        assertThat(payment.getAmount()).isEqualTo(order.getTotalPrice());
        return payment;
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
