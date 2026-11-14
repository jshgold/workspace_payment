package com.payment.product.service;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [전략 1] 락 없음 — 문제 재현용.
 * <p>
 * 조회 → 도메인 메서드로 차감 → 읽은 값 기준으로 계산한 재고를 그대로 덮어쓴다.
 * 여러 트랜잭션이 같은 stock 값을 읽고 각자 계산한 결과를 덮어써서 Lost Update가 발생한다.
 * 운영 코드로 쓰면 안 되고, 다른 전략과 비교하기 위한 기준선이다.
 * <p>
 * 더티 체킹으로 저장하지 않는 이유: Product에 {@code @Version}이 붙으면 더티 체킹 UPDATE에
 * {@code AND version=?}가 붙어 낙관적 락이 되어 버리기 때문이다. 그래서 엔티티를 영속성 컨텍스트에서
 * 분리(detach)한 뒤 계산하고, 버전 검사가 없는 벌크 UPDATE로 덮어쓴다.
 */
@Service
@RequiredArgsConstructor
public class NoLockStockDecreaseService implements StockDecreaseService {

    private final ProductRepository productRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void decrease(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        // 분리하지 않으면 벌크 UPDATE 전 auto flush로 더티 체킹 UPDATE(version 검사 포함)가 먼저 나간다.
        entityManager.detach(product);
        product.decreaseStock(quantity);
        productRepository.overwriteStock(productId, product.getStock(), product.getStatus());
    }
}
