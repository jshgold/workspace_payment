package com.payment.product.service;

import com.payment.product.infrastructure.NamedLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [전략 5] MySQL Named Lock Facade. (트랜잭션 없음)
 * <p>
 * {@code 락 획득 → 트랜잭션 시작 → 차감 → 커밋 → 락 해제} 순서를 {@link NamedLockExecutor}로 보장한다.
 * 락 이름은 {@code stock:{productId}}라서 같은 상품끼리만 직렬화되고, 다른 상품은 서로 막지 않는다.
 * 락 대기 시간은 3초이며, 초과하면 BusinessException(LOCK_ACQUISITION_FAILED)이 발생한다.
 */
@Component
@RequiredArgsConstructor
public class NamedLockStockDecreaseFacade implements StockDecreaseService {

    private static final String LOCK_NAME_PREFIX = "stock:";
    private static final int LOCK_TIMEOUT_SECONDS = 3;

    private final NamedLockExecutor namedLockExecutor;
    private final NamedLockStockDecreaseService namedLockStockDecreaseService;

    @Override
    public void decrease(Long productId, int quantity) {
        namedLockExecutor.executeWithLock(
                LOCK_NAME_PREFIX + productId,
                LOCK_TIMEOUT_SECONDS,
                () -> namedLockStockDecreaseService.decreaseInTransaction(productId, quantity));
    }
}
