package com.payment.product.domain;

import java.time.LocalDateTime;

/**
 * 재고 판정에 필요한 값만 담은 조회 결과. 엔티티가 아니라서 영속성 컨텍스트에 남지 않는다.
 * <p>
 * 결제 시작의 락 전 재고 조회에 쓴다. 엔티티로 먼저 읽으면 뒤의 {@code SELECT ... FOR UPDATE}가 행은 잠가도
 * 영속성 컨텍스트에 있는 예전 값을 돌려주므로, 락 안의 판정이 낡은 값으로 이뤄진다.
 * (docs/troubleshooting/payment-precheck-stock.md 5-1)
 */
public record ProductStockSnapshot(int stock, ProductStatus status, LocalDateTime deletedAt) {

    /** 판정 규칙은 {@link Product#canDecreaseStock}과 같다. 규칙은 이 메서드 한 곳에 둔다. */
    public boolean canDecreaseStock(int quantity) {
        return deletedAt == null && quantity > 0 && status != ProductStatus.STOPPED && stock >= quantity;
    }
}
