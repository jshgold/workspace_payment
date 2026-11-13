package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.product.domain.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 락 없음 전략의 동시성 테스트 — <b>Lost Update가 발생하는 것을 검증</b>한다.
 * <p>
 * 다른 전략은 공통 실험 조건({@link StockConcurrencyTestSupport})의 "정상 결과"를 기대하지만,
 * 이 전략은 문제 재현용 기준선이므로 같은 두 시나리오를 오버라이드해서 "정합성이 깨졌다"를 기대한다.
 * <p>
 * 정상이라면 {@code 성공 건수 × 수량 + 최종 재고 == 초기 재고}이다.
 * Lost Update가 있으면 성공으로 센 차감이 재고에 반영되지 않아 이 값이 초기 재고보다 커진다.
 * (재고가 부족한 시나리오에서는 같은 재고를 여러 스레드가 차감해 초과 판매되는 경우도 포함된다)
 */
class NoLockStockDecreaseServiceTest extends StockConcurrencyTestSupport {

    @Autowired
    private NoLockStockDecreaseService noLockStockDecreaseService;

    @Override
    protected StockDecreaseService strategy() {
        return noLockStockDecreaseService;
    }

    @Override
    protected String strategyName() {
        return "락 없음";
    }

    @Override
    @Test
    @DisplayName("재고 100에서 100개 스레드가 1개씩 차감하면 Lost Update로 성공 건수만큼 재고가 줄지 않는다")
    void stockEqualsRequests() throws InterruptedException {
        // given
        int initialStock = 100;
        Long productId = saveProduct(initialStock);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고100/요청100 (Lost Update 기대)");
        assertThat(result.success() * QUANTITY + found.getStock()).isGreaterThan(initialStock);
    }

    @Override
    @Test
    @DisplayName("재고 50에서 100개 스레드가 1개씩 차감하면 Lost Update로 성공 건수와 재고의 합이 초기 재고를 넘는다")
    void stockLessThanRequests() throws InterruptedException {
        // given
        int initialStock = 50;
        Long productId = saveProduct(initialStock);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고50/요청100 (Lost Update 기대)");
        assertThat(result.success() * QUANTITY + found.getStock()).isGreaterThan(initialStock);
    }
}
