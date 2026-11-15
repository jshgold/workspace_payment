package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 결제 승인 시나리오(정상, PG 실패, DB 실패 후 보상)를 실제 MySQL과 가짜 PG로 검증하는 공통 틀.
 * 동시성 제어 방식이 다른 {@link PaymentStateService} 구현체는 {@link #stateService()}만 바꿔서 같은 시나리오로 비교한다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다. 선점·반영 트랜잭션이 각각 실제로 커밋돼야 하기 때문이다.
 */
public abstract class PaymentFlowTestSupport extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;

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
        PaymentResponse response = paymentFacade.approve(order.getId());

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
        assertThatThrownBy(() -> paymentFacade.approve(order.getId())).isInstanceOf(PaymentDeclinedException.class);
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
        assertThatThrownBy(() -> paymentFacade.approve(order.getId())).isInstanceOf(PaymentTimeoutException.class);
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
        assertThatThrownBy(() -> paymentFacade.approve(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고가 부족합니다");
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
        assertThatThrownBy(() -> paymentFacade.approve(order.getId()))
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
