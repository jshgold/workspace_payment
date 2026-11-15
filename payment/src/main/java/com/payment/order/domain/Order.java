package com.payment.order.domain;

import java.time.LocalDateTime;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductStatus;

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
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 주문 이력 보존용 참조. 상품은 소프트 삭제되므로 FK가 깨지지 않는다. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /** 서버가 계산한 결제 예정 금액. 클라이언트 값은 받지 않는다. (3-3에서 PG 승인 금액과 비교) */
    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Order(Long memberId, Long productId, int quantity, long totalPrice, OrderStatus status) {
        this.memberId = memberId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.status = status;
    }

    /**
     * 주문을 생성한다. 재고는 차감하지 않고 "주문 가능 여부"만 사전 검증한다.
     * 실제 차감은 결제 승인 시점(3-3)에 락 전략으로 보장한다. 여기서 읽은 재고는 곧 낡을 수 있다.
     */
    public static Order create(Long memberId, Product product, int quantity) {
        if (memberId == null) {
            throw new IllegalArgumentException("회원 ID는 필수입니다.");
        }
        if (product == null) {
            throw new IllegalArgumentException("상품은 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 0보다 커야 합니다.");
        }
        if (product.isDeleted() || product.getStatus() != ProductStatus.ON_SALE) {
            throw new IllegalStateException("판매중인 상품이 아닙니다.");
        }
        if (product.getStock() < quantity) {
            throw new IllegalStateException(
                    "재고가 부족합니다. 현재 재고: " + product.getStock() + ", 주문 수량: " + quantity);
        }
        return Order.builder()
                .memberId(memberId)
                .productId(product.getId())
                .quantity(quantity)
                .totalPrice(Math.multiplyExact(product.getPrice(), (long) quantity))
                .status(OrderStatus.PENDING)
                .build();
    }
}
