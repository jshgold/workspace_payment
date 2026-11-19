package com.payment.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    private static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    /** 낙관적 락용 버전. UPDATE 시 WHERE version = ? 로 충돌을 감지하고 매번 1씩 증가한다. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Product(String name, long price, int stock, ProductStatus status) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.status = status;
    }

    public static Product create(String name, long price, int stock) {
        validate(name, price, stock);
        return Product.builder()
                .name(name)
                .price(price)
                .stock(stock)
                .status(stock == 0 ? ProductStatus.SOLD_OUT : ProductStatus.ON_SALE)
                .build();
    }

    /**
     * 이름·가격·재고를 수정한다. 판매 상태는 재고에 맞춰 자동 보정한다.
     * (재고 0 → SOLD_OUT, SOLD_OUT인데 재고가 생기면 ON_SALE. STOPPED는 그대로 유지)
     */
    public void update(String name, long price, int stock) {
        assertNotDeleted();
        validate(name, price, stock);
        this.name = name;
        this.price = price;
        this.stock = stock;
        syncStatusWithStock();
    }

    /**
     * 판매 상태를 직접 변경한다.
     * 재고와 모순되는 상태(재고 0인데 ON_SALE, 재고가 있는데 SOLD_OUT)는 허용하지 않는다.
     */
    public void changeStatus(ProductStatus newStatus) {
        assertNotDeleted();
        if (newStatus == null) {
            throw new IllegalArgumentException("판매 상태는 필수입니다.");
        }
        if (newStatus == ProductStatus.ON_SALE && stock == 0) {
            throw new IllegalStateException("재고가 없는 상품은 판매중으로 바꿀 수 없습니다.");
        }
        if (newStatus == ProductStatus.SOLD_OUT && stock > 0) {
            throw new IllegalStateException("재고가 남아 있는 상품은 품절로 바꿀 수 없습니다.");
        }
        this.status = newStatus;
    }

    /**
     * {@link #decreaseStock}이 예외 없이 성공할지 미리 확인한다. 결제 시작에서 재고 부족을 예외(롤백)가 아니라
     * "실패 기록 후 커밋"으로 처리하기 위해 쓴다. 판정 기준은 decreaseStock과 같다.
     */
    public boolean canDecreaseStock(int quantity) {
        return !isDeleted() && quantity > 0 && status != ProductStatus.STOPPED && stock >= quantity;
    }

    /**
     * 재고를 차감한다. 재고가 0이 되면 SOLD_OUT으로 바뀐다.
     * 이 메서드는 "읽은 시점의 stock" 기준으로 검증하므로, 동시 호출을 막는 것은 호출하는 쪽(락 전략)의 몫이다.
     */
    public void decreaseStock(int quantity) {
        assertNotDeleted();
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 0보다 커야 합니다.");
        }
        if (status == ProductStatus.STOPPED) {
            throw new IllegalStateException("판매중지 상품은 재고를 차감할 수 없습니다.");
        }
        if (stock < quantity) {
            throw new IllegalStateException("재고가 부족합니다. 현재 재고: " + stock + ", 요청 수량: " + quantity);
        }
        this.stock -= quantity;
        if (this.stock == 0) {
            this.status = ProductStatus.SOLD_OUT;
        }
    }

    /**
     * 결제 취소로 재고를 복구한다. 품절(SOLD_OUT)이었다면 판매중(ON_SALE)으로 돌아간다.
     * 삭제됐거나 판매중지된 상품도 복구한다. 이미 환불된 주문의 재고를 되돌리는 일이라 상품 상태 때문에 막으면 안 되고,
     * 판매중지(STOPPED) 상태는 그대로 유지한다. (decreaseStock과 동일하게 동시성 제어는 호출하는 쪽의 몫이다.)
     */
    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("복구 수량은 0보다 커야 합니다.");
        }
        this.stock = Math.addExact(this.stock, quantity);
        if (this.status == ProductStatus.SOLD_OUT) {
            this.status = ProductStatus.ON_SALE;
        }
    }

    /** 소프트 삭제. 행은 남기고 deleted_at만 기록한다. */
    public void delete() {
        assertNotDeleted();
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private void syncStatusWithStock() {
        if (status == ProductStatus.STOPPED) {
            return;
        }
        this.status = stock == 0 ? ProductStatus.SOLD_OUT : ProductStatus.ON_SALE;
    }

    private void assertNotDeleted() {
        if (isDeleted()) {
            throw new IllegalStateException("삭제된 상품입니다.");
        }
    }

    private static void validate(String name, long price, int stock) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("상품명은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다.");
        }
    }
}
