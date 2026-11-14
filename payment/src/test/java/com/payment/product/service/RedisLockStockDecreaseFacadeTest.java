package com.payment.product.service;

import com.payment.support.WithRedis;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Redis 분산 락(Redisson) 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 */
@WithRedis
class RedisLockStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    @Autowired
    private RedisLockStockDecreaseFacade redisLockStockDecreaseFacade;

    @Override
    protected StockDecreaseService strategy() {
        return redisLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "Redis 분산 락";
    }
}
