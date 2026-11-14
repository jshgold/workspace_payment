package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 6] Redis 분산 락 — 트랜잭션 구간.
 * <p>
 * 행 락은 걸지 않는다. 같은 상품에 대한 직렬화는 바깥의 {@link RedisLockStockDecreaseFacade}가 잡은 Redis 락이 맡고,
 * 여기서는 일반 조회 → 도메인 메서드로 차감 → 더티 체킹만 한다.
 * (StockDecreaseService를 구현하지 않는 이유: 구현체가 여럿이 되면 주입이 모호해지기 때문)
 */
@Service
@RequiredArgsConstructor
public class RedisLockStockDecreaseService {

    private final ProductRepository productRepository;

    @Transactional
    public void decreaseInTransaction(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }
}
