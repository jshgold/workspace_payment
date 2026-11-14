package com.payment.product.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * [전략 4] 낙관적 락 + 재시도 Facade. (트랜잭션 없음)
 * <p>
 * 매 시도마다 {@link OptimisticLockStockDecreaseService#decreaseOnce}를 호출해 새 트랜잭션·새 조회·새 version으로 시작한다.
 * 재시도 대상은 낙관적 락 충돌뿐이다. 재고 부족 같은 비즈니스 예외는 재시도해도 소용없으므로 그대로 던진다.
 * 충돌 직후 곧바로 다시 부딪히지 않도록 짧은 랜덤 대기(jitter)를 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimisticLockStockDecreaseFacade implements StockDecreaseService {

    static final int MAX_ATTEMPTS = 200;
    static final long MIN_BACKOFF_MS = 5;
    static final long MAX_BACKOFF_MS = 30;

    private final OptimisticLockStockDecreaseService stockDecreaseService;

    /** 실험 기록용: 충돌로 인해 발생한 재시도 누적 횟수 */
    private final AtomicLong retryCount = new AtomicLong();

    @Override
    public void decrease(Long productId, int quantity) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                stockDecreaseService.decreaseOnce(productId, quantity);
                return;
            } catch (OptimisticLockingFailureException e) {
                retryCount.incrementAndGet();
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("낙관적 락 재시도 한도 초과. productId={}, attempts={}", productId, attempt);
                    throw new BusinessException(ErrorCode.STOCK_CONFLICT_RETRY_EXCEEDED);
                }
                sleepBackoff();
            }
        }
        throw new BusinessException(ErrorCode.STOCK_CONFLICT_RETRY_EXCEEDED);
    }

    public long retryCount() {
        return retryCount.get();
    }

    public void resetRetryCount() {
        retryCount.set(0);
    }

    private void sleepBackoff() {
        long millis = ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MS, MAX_BACKOFF_MS + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.STOCK_CONFLICT_RETRY_EXCEEDED);
        }
    }
}
