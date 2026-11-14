package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.product.domain.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Named Lock을 <b>비즈니스 풀과 같은 풀</b>(크기 10)로 돌렸을 때의 풀 고갈 재현 실험.
 * <p>
 * 락 대기자 10명이 풀의 커넥션 10개를 전부 락용으로 잡으면, 락을 쥔 요청은 트랜잭션용 커넥션을 얻지 못한다.
 * 락 대기자들은 락이 풀리길, 락을 쥔 요청은 커넥션이 반납되길 서로 기다리다 커넥션 대기 한도(0.25초)로 실패한다.
 * 공통 실험 조건({@link StockConcurrencyTestSupport})의 "정상 결과"가 아니라 "풀 고갈로 정합성 목표(전부 성공)를 못 지킴"을 기대한다.
 */
@TestPropertySource(properties = {
        "app.named-lock.shared-pool=true",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.connection-timeout=250"
})
class NamedLockSharedPoolStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    @Autowired
    private NamedLockStockDecreaseFacade namedLockStockDecreaseFacade;

    @Override
    protected StockDecreaseService strategy() {
        return namedLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "Named Lock(공유 풀 10)";
    }

    @Override
    @Test
    @DisplayName("재고 100에서 100개 스레드가 1개씩 차감하면 공유 풀 고갈로 일부가 실패해 전부 성공하지 못한다")
    void stockEqualsRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(100);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고100/요청100 (풀 고갈 기대)");
        assertThat(result.fail()).isPositive();
        assertThat(result.success() * QUANTITY + found.getStock()).isEqualTo(100);
    }

    @Override
    @Test
    @DisplayName("재고 50에서 100개 스레드가 1개씩 차감하면 공유 풀 고갈로 재고가 있는데도 성공이 50에 못 미친다")
    void stockLessThanRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(50);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고50/요청100 (풀 고갈 기대)");
        assertThat(result.success()).isLessThan(50);
        assertThat(result.success() * QUANTITY + found.getStock()).isEqualTo(50);
    }
}
