package com.payment.order.domain;

import java.time.LocalDateTime;

import com.payment.common.util.UuidV7;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_orders_order_no", columnNames = {"order_no"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    private static final int ORDER_NO_LENGTH = 36;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 외부 노출용 주문번호(UUIDv7). PG에 보내는 orderId이기도 하다.
     * 내부 PK(순차 정수)를 PG나 API로 내보내면 주문 건수가 추측되므로 별도 값을 둔다. 한 번 정해지면 바꾸지 않는다.
     */
    @Column(name = "order_no", nullable = false, updatable = false, length = ORDER_NO_LENGTH)
    private String orderNo;

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
    private Order(String orderNo, Long memberId, Long productId, int quantity, long totalPrice, OrderStatus status) {
        this.orderNo = orderNo;
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
                .orderNo(UuidV7.generate().toString())
                .memberId(memberId)
                .productId(product.getId())
                .quantity(quantity)
                .totalPrice(Math.multiplyExact(product.getPrice(), (long) quantity))
                .status(OrderStatus.PENDING)
                .build();
    }

    /**
     * PG 승인 호출 전에 주문을 선점한다. PENDING일 때만 가능하다.
     * 동시에 들어온 두 번째 요청은 호출하는 쪽의 락 때문에 이 시점에 이미 PAYING을 보고 거절된다.
     */
    public void startPaying() {
        assertStatus(OrderStatus.PENDING, "결제를 시작할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.PAYING;
    }

    /** PG 승인 결과를 반영해 결제 완료로 바꾼다. PAYING일 때만 가능하다. */
    public void markPaid() {
        assertStatus(OrderStatus.PAYING, "결제 완료 처리할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.PAID;
    }

    /**
     * PG를 호출하기 전에 결제 시도를 거절하고 주문을 끝낸다. (재고 부족, 요청 금액 불일치)
     * 다시 결제하려면 새 주문을 만든다. 결제 시도 이전 상태로 되돌리는 간선은 없다.
     */
    public void rejectBeforePaying() {
        assertStatus(OrderStatus.PENDING, "결제 시작을 거절할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.FAILED;
    }

    /** 돈이 나가지 않은 것이 확정된 실패(PG 거절, 보상 취소 완료)로 주문을 끝낸다. 재고 복구는 호출하는 쪽이 같은 트랜잭션에서 한다. */
    public void markFailed() {
        assertStatus(OrderStatus.PAYING, "결제 실패 처리할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.FAILED;
    }

    /** PG 응답을 놓쳐 승인 여부를 모른다. 재고를 쥔 채 대사를 기다린다. */
    public void markPaymentUnknown() {
        assertStatus(OrderStatus.PAYING, "결제 결과 모름으로 바꿀 수 없는 주문 상태입니다.");
        this.status = OrderStatus.PAYMENT_UNKNOWN;
    }

    /** PG 취소 호출 전에 주문을 선점한다. 결제 완료(PAID) 주문만 취소할 수 있다. */
    public void startCanceling() {
        assertStatus(OrderStatus.PAID, "결제 취소를 시작할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.CANCELING;
    }

    /** PG 취소 결과를 반영해 취소 완료로 바꾼다. CANCELING일 때만 가능하다. */
    public void markCanceled() {
        assertStatus(OrderStatus.CANCELING, "취소 완료 처리할 수 없는 주문 상태입니다.");
        this.status = OrderStatus.CANCELED;
    }

    /** PG 취소 응답을 놓쳐 취소 여부를 모른다. 재고는 복구하지 않고 대사를 기다린다. */
    public void markCancelUnknown() {
        assertStatus(OrderStatus.CANCELING, "취소 결과 모름으로 바꿀 수 없는 주문 상태입니다.");
        this.status = OrderStatus.CANCEL_UNKNOWN;
    }

    /**
     * PG가 취소를 명확히 거절해 결제가 그대로 살아 있을 때 선점을 풀어 재시도를 허용한다.
     * 상태 전이표의 예외 간선이다. 확정된 결과를 지우는 것이 아니라 진행 중인 취소 시도가 무산된 것이다.
     */
    public void revertToPaid() {
        assertStatus(OrderStatus.CANCELING, "결제 완료로 되돌릴 수 없는 주문 상태입니다.");
        this.status = OrderStatus.PAID;
    }

    private void assertStatus(OrderStatus expected, String message) {
        if (this.status != expected) {
            throw new IllegalStateException(message + " 현재 상태: " + this.status);
        }
    }
}
