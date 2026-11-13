package com.payment.product.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 조건부 Atomic Update로 재고를 차감한다. (Product.decreaseStock 규칙을 SQL로 옮긴 것)
     * <ul>
     *   <li>WHERE: 삭제되지 않았고, 판매중(ON_SALE)이며, 재고가 충분할 때만 대상이 된다.</li>
     *   <li>SET 순서 주의: MySQL은 SET을 왼쪽에서 오른쪽으로 평가하므로,
     *       status를 stock보다 먼저 두어 "차감 전 stock" 기준(stock - qty = 0)으로 판정한다.</li>
     *   <li>벌크 연산은 영속성 컨텍스트를 거치지 않으므로 clearAutomatically로 1차 캐시를 비운다.</li>
     * </ul>
     *
     * @return 영향받은 행 수. 1이면 성공, 0이면 재고 부족 / 판매 불가 / 없는 상품
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Product p
               set p.status = case when p.stock - :quantity = 0
                                   then com.payment.product.domain.ProductStatus.SOLD_OUT
                                   else p.status end,
                   p.stock = p.stock - :quantity,
                   p.updatedAt = current_timestamp
             where p.id = :id
               and p.deletedAt is null
               and p.status = com.payment.product.domain.ProductStatus.ON_SALE
               and p.stock >= :quantity
            """)
    int decreaseStockIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);
}
