package com.payment.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDateTime;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.facade.PaymentFacade;
import com.payment.payment.infrastructure.FakePaymentClient;
import com.payment.payment.service.PessimisticLockPaymentStateService;
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
 * phase-04 4-4 주문 만료의 재현 테스트. <b>구현 후의 올바른 동작</b>을 기대한다.
 * 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2. 만료 기준 30분(intent 8절). 시간 경과는 생성·수정 시각을 과거로 옮겨 만든다.
 */
class OrderExpirationTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int STOCK = 5;
    private static final int QUANTITY = 2;

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
    private OrderExpirationService expirationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentFacade approveFacade;

    @BeforeEach
    void setUp() {
        fakePaymentClient.reset();
        approveFacade = new PaymentFacade(approveStateService, fakePaymentClient);
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        fakePaymentClient.reset();
    }

    @Test
    @DisplayName("E1 — 상품 1 × 재고 5, PENDING 31분 경과 주문과 29분 경과 주문 → 31분 주문만 EXPIRED, 29분 주문은 PENDING 그대로, 재고 5 그대로")
    void onlyOldPendingIsExpired() {
        // given
        Product product = productRepository.save(Product.create("키보드", PRICE, STOCK));
        Order old = saveOrder(product, 31);
        Order recent = saveOrder(product, 29);

        // when
        expirationService.expire();

        // then
        assertThat(orderStatus(old)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(orderStatus(recent)).isEqualTo(OrderStatus.PENDING);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .as("PENDING은 재고를 쥐지 않으므로 만료에서 재고를 건드리지 않는다").isEqualTo(STOCK);
    }

    @Test
    @DisplayName("E2 — PENDING 31분 경과 주문이 만료된 뒤 승인 요청이 오면 ORDER_NOT_PAYABLE(409)로 거절되고 PG 승인은 호출되지 않으며 결제 기록도 남지 않는다")
    void approveAfterExpirationIsRejected() {
        // given
        Product product = productRepository.save(Product.create("키보드", PRICE, STOCK));
        Order order = saveOrder(product, 31);
        expirationService.expire();

        // when
        BusinessException thrown = catchThrowableOfType(BusinessException.class,
                () -> approveFacade.approve(order.getId(), "key-" + order.getId(), order.getTotalPrice(),
                        "payment-key-" + order.getId()));

        // then
        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
        assertThat(fakePaymentClient.approveCallCount()).isZero();
        assertThat(paymentRepository.findByOrderId(order.getId())).isEmpty();
        assertThat(orderStatus(order)).isEqualTo(OrderStatus.EXPIRED);
    }

    /** 시각은 JVM 기준으로 넣는다. (엔티티의 @CreationTimestamp, @UpdateTimestamp와 같은 시계) */
    private Order saveOrder(Product product, int minutesAgo) {
        Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
        LocalDateTime at = LocalDateTime.now().minusMinutes(minutesAgo);
        jdbcTemplate.update("UPDATE orders SET created_at = ?, updated_at = ? WHERE id = ?", at, at, order.getId());
        return order;
    }

    private OrderStatus orderStatus(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }
}
