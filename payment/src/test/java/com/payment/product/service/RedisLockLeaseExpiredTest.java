package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import com.payment.product.domain.Product;
import com.payment.product.infrastructure.RedisLockExecutor;
import com.payment.support.WithRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 로직이 leaseTime보다 오래 걸리면 락이 먼저 풀려 상호 배제가 깨지는 것을 재현하는 실험.
 * <p>
 * leaseTime 200ms, 락 안의 작업은 400ms. 락이 작업 도중에 만료되어 다음 대기자가 임계구역에 들어오고,
 * 두 스레드가 동시에 임계구역 안에 있게 된다. 공통 실험 조건의 "정상 결과"가 아니라 "상호 배제 붕괴"를 기대한다.
 * (Product에 @Version이 있어서 겹친 쓰기는 낙관적 락 예외로 막힐 수 있으므로, 재고 정합성이 아니라
 * 임계구역 동시 진입 수와 만료 횟수로 판정한다.)
 */
@WithRedis
class RedisLockLeaseExpiredTest extends StockConcurrencyTestSupport {

    private static final long WAIT_TIME_MS = 2_000;
    private static final long LEASE_TIME_MS = 200;
    private static final long WORK_TIME_MS = 400;

    @Autowired
    private RedisLockExecutor redisLockExecutor;

    @Autowired
    private RedisLockStockDecreaseService redisLockStockDecreaseService;

    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();

    @BeforeEach
    void reset() {
        redisLockExecutor.resetLeaseExpiredCount();
    }

    @Override
    protected StockDecreaseService strategy() {
        return (productId, quantity) -> redisLockExecutor.executeWithLock(
                RedisLockStockDecreaseFacade.LOCK_NAME_PREFIX + productId,
                WAIT_TIME_MS,
                LEASE_TIME_MS,
                () -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(WORK_TIME_MS);
                        redisLockStockDecreaseService.decreaseInTransaction(productId, quantity);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inside.decrementAndGet();
                    }
                });
    }

    @Override
    protected String strategyName() {
        return "Redis 분산 락(leaseTime 초과)";
    }

    @Override
    @Test
    @DisplayName("재고 100에서 로직이 leaseTime보다 오래 걸리면 락이 먼저 만료되어 임계구역에 2개 이상이 동시에 들어간다")
    void stockEqualsRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(100);

        // when
        Result result = runConcurrently(productId);

        // then
        verifyMutualExclusionBroken(productId, result, "재고100/요청100 (lease 만료 기대)");
    }

    @Override
    @Test
    @DisplayName("재고 50에서 로직이 leaseTime보다 오래 걸리면 락이 먼저 만료되어 임계구역에 2개 이상이 동시에 들어간다")
    void stockLessThanRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(50);

        // when
        Result result = runConcurrently(productId);

        // then
        verifyMutualExclusionBroken(productId, result, "재고50/요청100 (lease 만료 기대)");
    }

    private void verifyMutualExclusionBroken(Long productId, Result result, String scenario) {
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, scenario);
        assertThat(redisLockExecutor.leaseExpiredCount()).isPositive();
        assertThat(maxInside.get()).isGreaterThan(1);
    }
}
