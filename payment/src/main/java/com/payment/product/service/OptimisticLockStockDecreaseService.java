package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 4] 낙관적 락 — 트랜잭션 1회 시도 단위.
 * <p>
 * 일반 조회 → 도메인 메서드로 차감 → 커밋 시점에 {@code UPDATE ... WHERE id=? AND version=?}.
 * 다른 트랜잭션이 먼저 커밋했다면 영향 행이 0건이 되어 {@code OptimisticLockingFailureException}이 발생한다.
 * <p>
 * 재시도는 여기서 하지 않는다. 충돌한 트랜잭션은 rollback-only이고, 같은 트랜잭션에서 다시 읽어도
 * 이전 스냅샷일 수 있기 때문이다. 재시도는 트랜잭션 바깥인 {@link OptimisticLockStockDecreaseFacade}가 맡는다.
 * (StockDecreaseService를 구현하지 않는 이유: 구현체가 둘이 되면 주입이 모호해지기 때문)
 */
@Service
@RequiredArgsConstructor
public class OptimisticLockStockDecreaseService {

    private final ProductRepository productRepository;

    @Transactional
    public void decreaseOnce(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }
}
