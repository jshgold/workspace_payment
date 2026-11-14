package com.payment.product.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 비관적 락 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 * SQL 로그에서 {@code for update}가 찍히는지 함께 확인한다.
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class PessimisticLockStockDecreaseServiceTest extends StockConcurrencyTestSupport {

    @Autowired
    private PessimisticLockStockDecreaseService pessimisticLockStockDecreaseService;

    @Override
    protected StockDecreaseService strategy() {
        return pessimisticLockStockDecreaseService;
    }

    @Override
    protected String strategyName() {
        return "비관적 락";
    }
}
