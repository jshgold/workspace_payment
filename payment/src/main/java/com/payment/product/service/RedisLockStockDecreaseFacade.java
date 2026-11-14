package com.payment.product.service;

import com.payment.product.infrastructure.RedisLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [전략 6] Redis 분산 락(Redisson) Facade. (트랜잭션 없음)
 * <p>
 * {@code 락 획득 → 트랜잭션 시작 → 차감 → 커밋 → 락 해제} 순서를 {@link RedisLockExecutor}로 보장한다.
 * 락 이름은 {@code lock:stock:{productId}}라서 같은 상품끼리만 직렬화되고, 다른 상품은 서로 막지 않는다.
 * <ul>
 *   <li>waitTime 10초: 100개 요청이 한 줄로 처리되는 시간보다 넉넉하게 잡았다.</li>
 *   <li>leaseTime 3초: 정상 로직(수 ms)보다 충분히 길게 잡았다. 이보다 오래 걸리면 락이 먼저 풀린다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisLockStockDecreaseFacade implements StockDecreaseService {

    static final String LOCK_NAME_PREFIX = "lock:stock:";
    static final long WAIT_TIME_MS = 10_000;
    static final long LEASE_TIME_MS = 3_000;

    private final RedisLockExecutor redisLockExecutor;
    private final RedisLockStockDecreaseService redisLockStockDecreaseService;

    @Override
    public void decrease(Long productId, int quantity) {
        redisLockExecutor.executeWithLock(
                LOCK_NAME_PREFIX + productId,
                WAIT_TIME_MS,
                LEASE_TIME_MS,
                () -> redisLockStockDecreaseService.decreaseInTransaction(productId, quantity));
    }
}
