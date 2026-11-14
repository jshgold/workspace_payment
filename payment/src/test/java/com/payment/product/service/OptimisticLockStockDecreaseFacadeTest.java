package com.payment.product.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 낙관적 락(+Facade 재시도) 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 * 시나리오마다 충돌로 인한 재시도 횟수를 로그로 남겨 다른 전략과 비교한다.
 */
@TestPropertySource(properties = "logging.level.org.hibernate.SQL=debug")
class OptimisticLockStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockStockDecreaseFacadeTest.class);

    @Autowired
    private OptimisticLockStockDecreaseFacade optimisticLockStockDecreaseFacade;

    @BeforeEach
    void resetRetryCount() {
        optimisticLockStockDecreaseFacade.resetRetryCount();
    }

    @AfterEach
    void logRetryCount() {
        log.info("[{}] 충돌로 인한 재시도 누적 {}회", strategyName(), optimisticLockStockDecreaseFacade.retryCount());
    }

    @Override
    protected StockDecreaseService strategy() {
        return optimisticLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "낙관적 락";
    }
}
