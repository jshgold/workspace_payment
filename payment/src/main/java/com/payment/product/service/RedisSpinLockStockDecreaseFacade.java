package com.payment.product.service;

import com.payment.product.infrastructure.RedisSpinLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [전략 6-변형] Redis 스핀락 Facade. (트랜잭션 없음)
 * <p>
 * {@code 락 획득(SET NX PX 폴링) → 트랜잭션 시작 → 차감 → 커밋 → 락 해제(Lua)} 순서를 {@link RedisSpinLockExecutor}로 보장한다.
 * 트랜잭션 구간은 Redisson 전략과 같은 {@link RedisLockStockDecreaseService}를 재사용한다.
 * 락 이름 접두사를 Redisson과 다르게 해서(Redisson은 hash, 여기는 string) 같은 키에서 타입이 충돌하지 않게 한다.
 * waitTime/leaseTime은 Redisson 전략과 같은 값(10초/3초)으로 공정하게 비교한다.
 */
@Component
@RequiredArgsConstructor
public class RedisSpinLockStockDecreaseFacade implements StockDecreaseService {

    static final String LOCK_NAME_PREFIX = "spinlock:stock:";

    private final RedisSpinLockExecutor redisSpinLockExecutor;
    private final RedisLockStockDecreaseService redisLockStockDecreaseService;

    @Override
    public void decrease(Long productId, int quantity) {
        redisSpinLockExecutor.executeWithLock(
                LOCK_NAME_PREFIX + productId,
                RedisLockStockDecreaseFacade.WAIT_TIME_MS,
                RedisLockStockDecreaseFacade.LEASE_TIME_MS,
                () -> redisLockStockDecreaseService.decreaseInTransaction(productId, quantity));
    }
}
