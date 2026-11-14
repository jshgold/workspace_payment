package com.payment.product.service;

import com.payment.product.infrastructure.RedisSpinLockExecutor;
import com.payment.support.RedisCommandStats;
import com.payment.support.WithRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis 스핀락 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 * 시나리오마다 SET NX 시도 횟수와 Redis 서버가 받은 전체 명령 수를 로그로 남겨 Redisson과 비교한다.
 */
@WithRedis
class RedisSpinLockStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisSpinLockStockDecreaseFacadeTest.class);

    @Autowired
    private RedisSpinLockStockDecreaseFacade redisSpinLockStockDecreaseFacade;

    @Autowired
    private RedisSpinLockExecutor redisSpinLockExecutor;

    @Value("${app.redis.address}")
    private String redisAddress;

    private RedisCommandStats stats;

    @BeforeEach
    void startMeasure() {
        redisSpinLockExecutor.resetCounters();
        stats = new RedisCommandStats(redisAddress);
        stats.mark();
    }

    @AfterEach
    void logMeasure() {
        log.info("[{}] SET NX 시도 {}회, Redis 처리 명령 {}건",
                strategyName(), redisSpinLockExecutor.attemptCount(), stats.commandsSinceMark());
        stats.close();
    }

    @Override
    protected StockDecreaseService strategy() {
        return redisSpinLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "Redis 스핀락";
    }
}
