package com.payment.payment.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * PG 승인이 끝난 결제 기록. Order와 1:1이다.
 * 같은 주문에 결제 기록이 둘 생기는 것은 {@code order_id} UNIQUE가 DB 수준에서 마지막으로 막는다.
 * (앱 쪽 방어선은 주문 상태 선점이고, 이 제약은 그게 뚫렸을 때를 위한 안전망이다.)
 */
@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_id", columnNames = {"order_id"}),
        @UniqueConstraint(name = "uk_payment_pg_transaction_id", columnNames = {"pg_transaction_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    /** 토스 paymentKey 최대 길이(200자)에 맞춘다. PG가 식별자 길이를 늘릴 수 있어 여유를 두지 않는다. */
    private static final int PG_TRANSACTION_ID_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "pg_transaction_id", nullable = false, length = PG_TRANSACTION_ID_MAX_LENGTH)
    private String pgTransactionId;

    /** PG가 실제로 승인한 금액. 서버 계산 금액과 일치함을 확인한 뒤에만 기록한다. */
    @Column(nullable = false)
    private long amount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Payment(Long orderId, String pgTransactionId, long amount) {
        this.orderId = orderId;
        this.pgTransactionId = pgTransactionId;
        this.amount = amount;
    }

    public static Payment create(Long orderId, String pgTransactionId, long amount) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (pgTransactionId == null || pgTransactionId.isBlank()) {
            throw new IllegalArgumentException("PG 거래 ID는 필수입니다.");
        }
        if (pgTransactionId.length() > PG_TRANSACTION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("PG 거래 ID는 " + PG_TRANSACTION_ID_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
        return Payment.builder()
                .orderId(orderId)
                .pgTransactionId(pgTransactionId)
                .amount(amount)
                .build();
    }
}
