package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;

import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.facade.PaymentCancelFacade;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * phase-04 4-3 취소 재설계의 재현 테스트. <b>재설계 후의 올바른 동작</b>을 기대한다.
 * 재설계 전에는 실패하는 것이 정상이고, 결과는 {@code docs/evidence/before-cancel-redesign.md}에 남긴다.
 * <p>
 * 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2. 승인은 실제 {@link PaymentFacade}로 거쳐 PAID 주문을 만든다.
 */
class PaymentCancelAttemptRecordTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.execute("DELETE FROM payment_cancel");
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("PG 취소 응답이 타임아웃되면 주문은 CANCEL_UNKNOWN이 되고, 재고는 복구되지 않으며 결제는 APPROVED로 남는다")
    void cancelTimeoutBecomesCancelUnknown() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willTimeoutCancel();

        // when
        catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));

        // then
        assertThat(orderStatus(order))
                .as("취소 응답을 놓친 주문은 진행 중(CANCELING)이 아니라 결과 모름(CANCEL_UNKNOWN)이어야 한다")
                .isEqualTo(OrderStatus.CANCEL_UNKNOWN);
        assertThat(stock(product)).as("취소가 확정되기 전에는 재고를 복구하지 않는다").isEqualTo(3);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("PG가 취소를 거절하면 취소 시도가 FAILED와 실패 코드로 남고, 주문은 PAID로 돌아간다")
    void cancelDeclinedIsRecorded() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        fakePaymentClient.willFailCancel();

        // when
        catchThrowable(() -> cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심"));

        // then
        List<Map<String, Object>> attempts = jdbcTemplate.queryForList(
                "SELECT status, failure_code FROM payment_cancel WHERE order_id = ?", order.getId());
        assertThat(attempts).as("거절된 취소 시도도 기록으로 남아야 한다").hasSize(1);
        assertThat(attempts.getFirst().get("status")).isEqualTo("FAILED");
        assertThat(attempts.getFirst().get("failure_code")).isEqualTo("PAYMENT_CANCEL_DECLINED");
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("취소에 성공한 뒤 같은 요청이 재전송되면 같은 결과(CANCELED)를 받고 PG 취소는 다시 호출되지 않는다")
    void resentCancelAfterSuccessGetsSameResult() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product, 2);
        approve(order);
        PaymentResponse first = cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심");

        // when: 응답을 놓친 클라이언트가 같은 요청을 다시 보낸다
        PaymentResponse resent = cancelFacade.cancel(order.getId(), "cancel-key-" + order.getId(), "단순 변심");

        // then
        assertThat(resent.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(resent).isEqualTo(first);
        assertThat(fakePaymentClient.cancelCallCount()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(5);
    }

    private void approve(Order order) {
        approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                "payment-key-" + order.getId());
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

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
