package com.payment.product.service;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * MySQL Named Lock 전략의 동시성 테스트. (락 전용 풀 분리 상태)
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 */
class NamedLockStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    @Autowired
    private NamedLockStockDecreaseFacade namedLockStockDecreaseFacade;

    @Override
    protected StockDecreaseService strategy() {
        return namedLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "Named Lock";
    }
}
