package com.payment.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.payment.domain.PaymentRepository;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.support.IntegrationTestSupport;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [락 전 재고 조회] {@code docs/troubleshooting/payment-precheck-stock.md}
 * <p>
 * 상품 행 락은 테스트가 직접 연 JDBC 커넥션으로 쥔다. 이 커넥션을 커밋하기 전까지 다른 트랜잭션의
 * {@code SELECT ... FOR UPDATE}는 기다린다. 결제 시작이 그 줄에 서는지 여부로 락 전 조회가 동작하는지 본다.
 */
@Slf4j
@Tag("concurrency")
class PessimisticLockStockPrecheckTest extends IntegrationTestSupport {

    private static final long PRICE = 10_000L;
    private static final int QUANTITY = 1;

    @Autowired
    private PessimisticLockPaymentStateService stateService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("재고 0인 상품의 행 락을 다른 트랜잭션이 쥐고 있어도, 결제 시작은 락을 기다리지 않고 STOCK_NOT_AVAILABLE로 거절하고 실패를 기록한다")
    void soldOutRequestDoesNotWaitForProductLock() throws Exception {
        // given: 주문을 만든 뒤 재고가 0이 됐고, 다른 트랜잭션이 상품 행 락을 쥐고 있다
        Product product = productRepository.save(Product.create("키보드", PRICE, 1));
        Order order = orderRepository.save(Order.create(100L, product, QUANTITY));
        jdbcTemplate.update("UPDATE product SET stock = 0, status = 'SOLD_OUT' WHERE id = ?", product.getId());

        StartPayingResult result;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockProductRow(holder, product.getId());

            // when
            long start = System.nanoTime();
            result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> stateService.startPaying(command(order)),
                    "재고 0이면 상품 행 락 줄에 서지 않고 바로 거절해야 한다");
            log.info("락 전 조회 거절 소요 시간: {}ms", (System.nanoTime() - start) / 1_000_000);

            holder.rollback();
        }

        // then
        assertThat(result).isEqualTo(new StartPayingResult.Rejected(ErrorCode.STOCK_NOT_AVAILABLE));
        assertThat(orderStatus(order)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForMap("SELECT status, failure_code FROM payment WHERE order_id = ?", order.getId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", ErrorCode.STOCK_NOT_AVAILABLE.name());
        assertThat(stock(product)).isZero();
    }

    @Test
    @DisplayName("락 전 조회를 통과한 뒤 다른 트랜잭션이 마지막 재고를 가져가면, 락을 잡은 뒤 최신 재고로 다시 판정해 STOCK_NOT_AVAILABLE로 거절한다")
    void recheckUnderLockUsesLatestStock() throws Exception {
        // given: 재고 1. 다른 트랜잭션이 마지막 재고를 차감했지만 아직 커밋하지 않았다
        Product product = productRepository.save(Product.create("키보드", PRICE, 1));
        Order order = orderRepository.save(Order.create(100L, product, QUANTITY));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<StartPayingResult> future;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "UPDATE product SET stock = 0, status = 'SOLD_OUT', version = version + 1 WHERE id = ?")) {
                ps.setLong(1, product.getId());
                ps.executeUpdate();
            }

            // when: 결제 시작은 커밋 전 값(재고 1)을 보고 락 전 조회를 통과해 상품 행 락 줄에 선다
            future = CompletableFuture.supplyAsync(() -> stateService.startPaying(command(order)), executor);
            Thread.sleep(500);
            assertThat(future)
                    .as("락 전 조회를 통과했다면 상품 행 락을 기다리고 있어야 한다")
                    .isNotDone();

            holder.commit();
        }

        // then: 락을 잡은 뒤에는 커밋된 최신 재고(0)로 판정한다
        StartPayingResult result = future.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(result).isEqualTo(new StartPayingResult.Rejected(ErrorCode.STOCK_NOT_AVAILABLE));
        assertThat(orderStatus(order)).isEqualTo("FAILED");
        assertThat(stock(product)).as("초과 판매가 없어야 한다").isZero();
    }

    private StartPayingCommand command(Order order) {
        return new StartPayingCommand(order.getId(), "key-" + order.getId(), PRICE * QUANTITY, "payment-key-" + order.getId());
    }

    private void lockProductRow(Connection connection, Long productId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, productId);
            ps.executeQuery().close();
        }
    }

    private String orderStatus(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId());
    }

    private int stock(Product product) {
        return jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, product.getId());
    }
}
