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
