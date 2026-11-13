package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 2] 조건부 Atomic Update.
 * <p>
 * 조회 없이 {@code UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?} 한 문장으로
 * "재고 확인 + 차감 + SOLD_OUT 전환"을 DB에게 맡긴다. 판단(재고 충분 여부)이 SQL 안에 있으므로
 * 자바 쪽에는 락 없이 읽은 값으로 판단하는 구간이 없다.
 * 영향받은 행 수가 0이면 차감 실패로 본다.
 */
@Service
@RequiredArgsConstructor
public class AtomicUpdateStockDecreaseService implements StockDecreaseService {

    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void decrease(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 0보다 커야 합니다.");
        }
        int updatedRows = productRepository.decreaseStockIfAvailable(productId, quantity);
        if (updatedRows == 0) {
            // 실패 경로에서만 원인을 구분한다. (없는 상품 vs 재고 부족/판매 불가)
            if (!productRepository.existsById(productId)) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.STOCK_NOT_AVAILABLE);
        }
    }
}
