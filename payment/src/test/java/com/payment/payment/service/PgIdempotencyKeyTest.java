package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentCancel;
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
import com.payment.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PG 호출 멱등성(phase-04 4-5)을 실제 MySQL과 가짜 PG로 검증한다.
 * 가짜 PG는 토스처럼 PG 멱등키별로 첫 응답을 저장해 두고, 같은 키로 다시 오면 처리하지 않고 그 응답을 돌려준다.
 * <p>
 * 핵심 장면: PG는 처리했는데 응답이 오는 길에 끊김 → 같은 PG 키로 1번 다시 물음 → 첫 결과를 받아 그 요청 안에서 확정.
 * "호출 횟수"(우리가 보낸 횟수)와 "처리 횟수"(PG가 실제로 승인·취소한 횟수)를 나눠 본다.
 * <p>
 * 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (승인 후 재고 3)
 */
class PgIdempotencyKeyTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
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

    @Test
    @DisplayName("승인 응답 유실: PG는 승인했는데 응답이 끊기면 같은 PG 키(confirm-주문번호)로 1번 다시 묻고, PG는 다시 승인하지 않고 첫 결과를 돌려줘 그 요청 안에서 PAID가 된다 (호출 2번, 승인 1번)")
    void lostApproveResponseIsRecoveredByRetryWithSameKey() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.willLoseApproveResponseOnce();

        // when
        PaymentResponse response = approve(order);

        // then
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(stock(product)).isEqualTo(3);
        assertThat(fakePaymentClient.approveCallCount()).as("첫 호출 + 재시도").isEqualTo(2);
        assertThat(fakePaymentClient.approveProcessedCount()).as("PG가 실제로 승인한 횟수").isEqualTo(1);
        assertThat(fakePaymentClient.approveIdempotencyKeys())
                .containsExactly("confirm-" + order.getOrderNo(), "confirm-" + order.getOrderNo());
    }

    @Test
    @DisplayName("승인 응답 유실 후 같은 클라이언트 키로 재전송하면 PG를 부르지 않고 첫 결과(PAID)를 돌려준다")
    void clientReplayAfterRecoveredApproveDoesNotCallPg() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.willLoseApproveResponseOnce();
        approve(order);

        // when
        PaymentResponse replayed = approve(order);

        // then
        assertThat(replayed.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(2);
        assertThat(fakePaymentClient.approveProcessedCount()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    @DisplayName("승인 거절은 확정된 결과라 다시 묻지 않는다: PG 호출 1번, 주문·결제 FAILED, 재고 복구")
    void declinedApproveIsNotRetried() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        fakePaymentClient.willDecline("한도 초과");

        // when
        catchThrowable(() -> approve(order));

        // then
        assertThat(fakePaymentClient.approveCallCount()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.FAILED);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.FAILED);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("취소 응답 유실: PG는 취소했는데 응답이 끊기면 같은 PG 키(cancel-취소시도ID)로 1번 다시 묻고 첫 결과를 받아 그 요청 안에서 CANCELED가 된다 (호출 2번, 취소 1번, 재고 복구)")
    void lostCancelResponseIsRecoveredByRetryWithSameKey() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        approve(order);
        fakePaymentClient.willLoseCancelResponseOnce();

        // when
        PaymentResponse response = cancelFacade.cancel(order.getId(), "c1", REASON);

        // then
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(paymentStatus(order)).isEqualTo(PaymentStatus.CANCELED);
        List<PaymentCancel> attempts = attempts(order);
        assertThat(attempts).extracting(PaymentCancel::getStatus).containsExactly(PaymentCancelStatus.COMPLETED);
        assertThat(stock(product)).isEqualTo(5);
        assertThat(fakePaymentClient.cancelCallCount()).as("첫 호출 + 재시도").isEqualTo(2);
        assertThat(fakePaymentClient.cancelProcessedCount()).as("PG가 실제로 취소한 횟수").isEqualTo(1);
        String key = "cancel-" + attempts.getFirst().getId();
        assertThat(fakePaymentClient.cancelIdempotencyKeys()).containsExactly(key, key);
    }

    @Test
    @DisplayName("취소가 거절된 뒤 새 클라이언트 키로 다시 취소하면 새 취소 시도의 PG 키로 보내므로 1차의 거절을 돌려받지 않고 취소된다")
    void secondCancelAfterDeclineUsesNewPgKey() {
        // given
        Product product = saveProduct(5);
        Order order = saveOrder(product);
        approve(order);
        fakePaymentClient.willFailCancel();
        Throwable first = catchThrowable(() -> cancelFacade.cancel(order.getId(), "c1", REASON));
        assertThat(errorCode(first)).isEqualTo(ErrorCode.PAYMENT_CANCEL_DECLINED);
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.PAID);

        // when
        fakePaymentClient.reset();
        PaymentResponse second = cancelFacade.cancel(order.getId(), "c2", REASON);

        // then
        assertThat(second.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        List<PaymentCancel> attempts = attempts(order);
        assertThat(attempts).extracting(PaymentCancel::getStatus)
                .containsExactly(PaymentCancelStatus.FAILED, PaymentCancelStatus.COMPLETED);
        assertThat(fakePaymentClient.cancelIdempotencyKeys())
                .as("2차 취소는 1차와 다른 PG 키로 나가야 한다")
                .containsExactly("cancel-" + attempts.get(1).getId());
        assertThat(attempts.get(1).getId()).isNotEqualTo(attempts.get(0).getId());
        assertThat(stock(product)).isEqualTo(5);
    }

    /** 주문마다 다른 클라이언트 멱등키와 paymentKey로 결제한다. */
    private PaymentResponse approve(Order order) {
        return approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                "payment-key-" + order.getId());
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

    private Order saveOrder(Product product) {
        return orderRepository.save(Order.create(100L, product, 2));
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private PaymentStatus paymentStatus(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus();
    }
}
