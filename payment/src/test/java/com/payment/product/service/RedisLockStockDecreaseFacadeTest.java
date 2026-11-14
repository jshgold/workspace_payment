package com.payment.product.service;

import com.payment.support.RedisCommandStats;
import com.payment.support.WithRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis 분산 락(Redisson) 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있다.
 * 시나리오마다 Redis 서버가 받은 전체 명령 수를 로그로 남겨 스핀락과 같은 기준으로 비교한다.
 */
@WithRedis
class RedisLockStockDecreaseFacadeTest extends StockConcurrencyTestSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisLockStockDecreaseFacadeTest.class);

    @Autowired
    private RedisLockStockDecreaseFacade redisLockStockDecreaseFacade;

    @Value("${app.redis.address}")
    private String redisAddress;

    private RedisCommandStats stats;

    @BeforeEach
    void startMeasure() {
        stats = new RedisCommandStats(redisAddress);
        stats.mark();
    }

    @AfterEach
    void logMeasure() {
        log.info("[{}] Redis 처리 명령 {}건", strategyName(), stats.commandsSinceMark());
        stats.close();
    }

    @Override
    protected StockDecreaseService strategy() {
        return redisLockStockDecreaseFacade;
    }

    @Override
    protected String strategyName() {
        return "Redis 분산 락";
    }
}
