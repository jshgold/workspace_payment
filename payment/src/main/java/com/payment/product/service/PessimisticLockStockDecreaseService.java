package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 3] 비관적 락 (PESSIMISTIC_WRITE).
 * <p>
 * {@code SELECT ... FOR UPDATE}로 행을 잠근 채 조회 → 도메인 메서드로 차감 → 더티 체킹으로 UPDATE.
 * 락은 트랜잭션이 끝날 때(커밋/롤백)까지 유지되므로, 다른 트랜잭션은 FOR UPDATE 조회 지점에서 대기하고
 * 앞선 트랜잭션이 커밋한 최신 재고를 읽게 된다. (Lost Update 방지)
 * 락 대기 시간은 3초(repository 힌트)이며, 초과 시 락 예외가 발생한다.
 */
@Service
@RequiredArgsConstructor
public class PessimisticLockStockDecreaseService implements StockDecreaseService {

    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void decrease(Long productId, int quantity) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }
}
