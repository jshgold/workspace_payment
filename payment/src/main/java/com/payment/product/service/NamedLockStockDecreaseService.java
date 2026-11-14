package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 5] Named Lock — 트랜잭션 구간.
 * <p>
 * 행 락은 걸지 않는다. 같은 상품에 대한 직렬화는 바깥의 {@link NamedLockStockDecreaseFacade}가 잡은 Named Lock이 맡고,
 * 여기서는 일반 조회 → 도메인 메서드로 차감 → 더티 체킹만 한다.
 * 이 트랜잭션은 Named Lock을 잡은 커넥션과는 다른 커넥션을 풀에서 빌려 쓴다.
 * (StockDecreaseService를 구현하지 않는 이유: 구현체가 둘이 되면 주입이 모호해지기 때문)
 */
@Service
@RequiredArgsConstructor
public class NamedLockStockDecreaseService {

    private final ProductRepository productRepository;

    @Transactional
    public void decreaseInTransaction(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }
}
