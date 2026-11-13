package com.payment.product.service;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 락 없음 전략의 동시성 테스트.
 * <p>
 * 이 테스트는 <b>실패하는 것이 정상</b>이다. (Lost Update로 최종 재고가 0이 되지 않음)
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 */
class NoLockStockDecreaseServiceTest extends StockConcurrencyTestSupport {

    @Autowired
    private NoLockStockDecreaseService noLockStockDecreaseService;

    @Override
    protected StockDecreaseService strategy() {
        return noLockStockDecreaseService;
    }

    @Override
    protected String strategyName() {
        return "락 없음";
    }
}
