package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 1] 락 없음 — 문제 재현용.
 * <p>
 * 조회 → 도메인 메서드로 차감 → 더티 체킹으로 UPDATE.
 * 여러 트랜잭션이 같은 stock 값을 읽고 각자 계산한 결과를 덮어써서 Lost Update가 발생한다.
 * 운영 코드로 쓰면 안 되고, 다른 전략과 비교하기 위한 기준선이다.
 */
@Service
@RequiredArgsConstructor
public class NoLockStockDecreaseService implements StockDecreaseService {

    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void decrease(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }
}
