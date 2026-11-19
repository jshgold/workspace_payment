package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentTimeoutException;
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
 * [phase-04 4-2+ 재현 테스트] Payment를 "결제 시도 기록"으로 바꾼 뒤의 올바른 동작을 기대한다.
 * 재설계 전 코드(tag {@code before-payment-redesign})에서는 실패하는 것이 정상이며, 그 출력은
 * {@code docs/evidence/before-redesign.md}에 남겼다. 재설계 후에는 같은 테스트가 통과해야 한다.
 * <p>
 * 아직 없는 상태 값({@code FAILED}, {@code PAYMENT_UNKNOWN})과 컬럼({@code payment.status}, {@code failure_code})을
 * 다루므로, 컴파일되도록 enum 이름 문자열과 JDBC 조회로 검증한다. 재설계 후 타입으로 바꿔도 된다.
 * <p>
 * 실제 API 진입점과 같은 {@link PaymentFacade}(멱등키 포함)로 요청한다.
 */
public abstract class PaymentAttemptRecordTestSupport extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;
    private static final int INITIAL_STOCK = 5;
    private static final String PAYMENT_KEY = "payment-key-1";

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected FakePaymentClient fakePaymentClient;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected PaymentFacade approvalFacade;

    protected abstract PaymentStateService stateService();

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        approvalFacade = new PaymentFacade(stateService(), fakePaymentClient);
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("PG가 승인을 거절하면 Payment 행이 FAILED와 실패 코드로 남고, 재고는 원래대로 복구된다")
    void declineLeavesFailedPaymentRecord() {
        // given
        Product product = saveProduct();
        Order order = saveOrder(product);
        fakePaymentClient.willDecline("한도 초과");

        // when
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT, PAYMENT_KEY))
                .isInstanceOf(PaymentDeclinedException.class);

        // then
        assertThat(findPayment(order))
                .as("거절된 결제 시도도 Payment 행으로 남아야 한다")
                .isPresent();
        Map<String, Object> row = paymentStatusRow(order);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isNotNull();
        assertThat(stock(product)).isEqualTo(INITIAL_STOCK);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG가 승인을 거절하면 주문은 FAILED로 끝나고, 새 키로 같은 주문을 다시 결제해도 거절되며 PG를 다시 부르지 않는다")
    void declinedOrderCannotBeRetried() {
        // given
        Product product = saveProduct();
        Order order = saveOrder(product);
        fakePaymentClient.willDecline("한도 초과");
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT, PAYMENT_KEY))
                .isInstanceOf(PaymentDeclinedException.class);

        // when
        fakePaymentClient.willApprove();
        Throwable retry = catchRetry(() -> approvalFacade.approve(order.getId(), "key-2", AMOUNT, "payment-key-2"));

        // then
        assertThat(orderStatus(order))
                .as("확정 실패한 주문은 FAILED로 종료해야 한다 (재시도는 새 주문)")
                .isEqualTo("FAILED");
        assertThat(retry).as("FAILED 주문의 재결제는 거절되어야 한다").isNotNull();
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("PG 응답이 타임아웃되면 Payment 행이 paymentKey와 금액을 담은 채 남고, 재고는 차감된 채로 유지된다")
    void timeoutLeavesPaymentRecord() {
        // given
        Product product = saveProduct();
        Order order = saveOrder(product);
        fakePaymentClient.willTimeout();

        // when
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT, PAYMENT_KEY))
                .isInstanceOf(PaymentTimeoutException.class);

        // then
        Optional<Payment> payment = findPayment(order);
        assertThat(payment)
                .as("결과를 모르는 결제 시도도 Payment 행으로 남아야 한다 (무엇을 시도했는지)")
                .isPresent();
        assertThat(payment.get().getPaymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(payment.get().getAmount()).isEqualTo(AMOUNT);
        assertThat(stock(product)).isEqualTo(INITIAL_STOCK - QUANTITY);
    }

    @Test
    @DisplayName("PG 응답이 타임아웃되면 주문은 PAYMENT_UNKNOWN이 되고, 같은 키의 재요청은 '처리 중'을 받으며 PG를 다시 부르지 않는다")
    void timeoutMovesOrderToPaymentUnknown() {
        // given
        Product product = saveProduct();
        Order order = saveOrder(product);
        fakePaymentClient.willTimeout();

        // when
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT, PAYMENT_KEY))
                .isInstanceOf(PaymentTimeoutException.class);

        // then
        assertThat(orderStatus(order))
                .as("응답을 놓친 주문은 진행 중(PAYING)이 아니라 결과 모름(PAYMENT_UNKNOWN)이어야 한다")
                .isEqualTo("PAYMENT_UNKNOWN");
        assertThatThrownBy(() -> approvalFacade.approve(order.getId(), "key-1", AMOUNT, PAYMENT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
    }

    private Throwable catchRetry(Runnable retry) {
        try {
            retry.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private Product saveProduct() {
        return productRepository.save(Product.create("키보드", PRICE, INITIAL_STOCK));
    }

    private Order saveOrder(Product product) {
        return orderRepository.save(Order.create(100L, product, QUANTITY));
    }

    private Optional<Payment> findPayment(Order order) {
        return paymentRepository.findByOrderId(order.getId());
    }

    private Map<String, Object> paymentStatusRow(Order order) {
        return jdbcTemplate.queryForMap("SELECT status, failure_code FROM payment WHERE order_id = ?", order.getId());
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private String orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus().name();
    }
}
