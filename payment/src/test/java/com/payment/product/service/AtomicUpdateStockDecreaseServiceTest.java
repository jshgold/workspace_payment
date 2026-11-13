package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 조건부 Atomic Update 전략의 동시성 테스트.
 * 공통 실험 조건은 {@link StockConcurrencyTestSupport}에 있고, 아래는 SQL로 옮긴 도메인 규칙 검증이다.
 */
class AtomicUpdateStockDecreaseServiceTest extends StockConcurrencyTestSupport {

    @Autowired
    private AtomicUpdateStockDecreaseService atomicUpdateStockDecreaseService;

    @Override
    protected StockDecreaseService strategy() {
        return atomicUpdateStockDecreaseService;
    }

    @Override
    protected String strategyName() {
        return "조건부 Atomic Update";
    }

    @Test
    @DisplayName("재고가 남는 차감은 재고만 줄고 상태는 ON_SALE을 유지한다")
    void keepOnSaleWhenStockRemains() {
        // given
        Long productId = productRepository.save(Product.create("부분 차감", 1000, 10)).getId();

        // when
        atomicUpdateStockDecreaseService.decrease(productId, 3);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        assertThat(found.getStock()).isEqualTo(7);
        assertThat(found.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("재고를 정확히 0으로 만드는 차감은 같은 UPDATE에서 SOLD_OUT으로 전환된다")
    void soldOutInSameUpdate() {
        // given
        Long productId = productRepository.save(Product.create("전량 차감", 1000, 5)).getId();

        // when
        atomicUpdateStockDecreaseService.decrease(productId, 5);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        assertThat(found.getStock()).isZero();
        assertThat(found.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("재고보다 많은 수량을 요청하면 STOCK_NOT_AVAILABLE 예외가 나고 재고는 그대로다")
    void failWhenStockIsInsufficient() {
        // given
        Long productId = productRepository.save(Product.create("재고 부족", 1000, 2)).getId();

        // when & then
        assertThatThrownBy(() -> atomicUpdateStockDecreaseService.decrease(productId, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_AVAILABLE));
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(2);
    }

    @Test
    @DisplayName("판매중지(STOPPED) 상품은 차감되지 않고, 재고를 0으로 만드는 요청이어도 상태가 SOLD_OUT으로 덮어써지지 않는다")
    void stoppedProductIsNotDecreased() {
        // given
        Product product = Product.create("판매중지", 1000, 5);
        product.changeStatus(ProductStatus.STOPPED);
        Long productId = productRepository.save(product).getId();

        // when & then
        assertThatThrownBy(() -> atomicUpdateStockDecreaseService.decrease(productId, 5))
                .isInstanceOf(BusinessException.class);
        Product found = productRepository.findById(productId).orElseThrow();
        assertThat(found.getStock()).isEqualTo(5);
        assertThat(found.getStatus()).isEqualTo(ProductStatus.STOPPED);
    }

    @Test
    @DisplayName("존재하지 않는 상품이면 PRODUCT_NOT_FOUND 예외가 난다")
    void failWhenProductNotFound() {
        // when & then
        assertThatThrownBy(() -> atomicUpdateStockDecreaseService.decrease(-1L, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
