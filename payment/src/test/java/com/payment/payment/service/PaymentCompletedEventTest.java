package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicReference;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCompletedEvent;
import com.payment.payment.domain.PaymentNotifier;
import com.payment.payment.domain.PaymentRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 결제 완료 이벤트(3-6)를 실제 MySQL로 검증한다. 후처리(PaymentNotifier)만 목으로 바꿔 호출 여부와 스레드를 본다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다. 커밋돼야 AFTER_COMMIT 리스너가 실행되기 때문이다.
 */
class PaymentCompletedEventTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;

    enum Strategy { PESSIMISTIC_LOCK, ATOMIC_UPDATE }

    @MockitoBean
    private PaymentNotifier paymentNotifier;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private FakePaymentClient fakePaymentClient;

    @Autowired
    private PessimisticLockPaymentStateService pessimisticStateService;

    @Autowired
    private AtomicUpdatePaymentStateService atomicStateService;

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    @DisplayName("결제가 커밋되면 결제 완료 후처리가 정확히 1번, 호출 스레드와 다른 전용 스레드에서 실행된다")
    void handledAfterCommitOnAsyncThread(Strategy strategy) {
        // given
        AtomicReference<String> handlerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            handlerThread.set(Thread.currentThread().getName());
            return null;
        }).when(paymentNotifier).notifyPaid(any());
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);

        // when
        PaymentResponse response = facade(strategy).approve(order.getId());

        // then
        ArgumentCaptor<PaymentCompletedEvent> captor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(paymentNotifier, timeout(2000).times(1)).notifyPaid(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PaymentCompletedEvent(
                order.getId(), response.paymentId(), response.pgTransactionId(), 2 * PRICE));
        assertThat(handlerThread.get())
                .isNotEqualTo(Thread.currentThread().getName())
                .startsWith("payment-event-");
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    @DisplayName("결제 트랜잭션이 롤백되면(재고 부족) 후처리는 실행되지 않고, 주문과 결제 기록도 남지 않는다")
    void notHandledWhenTransactionRollsBack(Strategy strategy) {
        // given: 주문 생성 시점에는 재고가 충분했지만, 결제 반영 전에 재고가 줄어든 상황
        Product product = saveProduct(5);
        Order order = saveOrder(product, 3);
        drainStock(product, 4);

        // when // then
        assertThatThrownBy(() -> facade(strategy).approve(order.getId()))
                .isInstanceOf(RuntimeException.class);
        verify(paymentNotifier, after(500).never()).notifyPaid(any());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentRepository.findByOrderId(order.getId())).isEmpty();
        assertThat(fakePaymentClient.cancelCallCount()).as("보상 PG 취소는 호출된다").isEqualTo(1);
    }

    @Test
    @DisplayName("이미 PAID인 주문의 complete가 중복 호출돼도 후처리는 1번만 실행된다")
    void duplicateCompleteDoesNotPublishTwice() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 1);
        pessimisticStateService.startPaying(order.getId());
        PgApproveResult approveResult = new PgApproveResult("pg-tx-dup", PRICE);

        // when
        pessimisticStateService.complete(order.getId(), approveResult);
        pessimisticStateService.complete(order.getId(), approveResult);

        // then
        verify(paymentNotifier, timeout(2000).times(1)).notifyPaid(any());
        verify(paymentNotifier, after(500).times(1)).notifyPaid(any());
    }

    private PaymentFacade facade(Strategy strategy) {
        PaymentStateService stateService = switch (strategy) {
            case PESSIMISTIC_LOCK -> pessimisticStateService;
            case ATOMIC_UPDATE -> atomicStateService;
        };
        return new PaymentFacade(stateService, fakePaymentClient);
    }

    private Product saveProduct(int stock) {
        return productRepository.save(Product.create("키보드", PRICE, stock));
    }

    private Order saveOrder(Product product, int quantity) {
        return orderRepository.save(Order.create(100L, product, quantity));
    }

    private void drainStock(Product product, int quantity) {
        Product found = productRepository.findById(product.getId()).orElseThrow();
        found.decreaseStock(quantity);
        productRepository.save(found);
    }
}
