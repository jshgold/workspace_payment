package com.payment.payment.domain;

import java.time.LocalDateTime;

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

/**
 * 결제 승인 요청 한 번의 시도를 식별하는 멱등키 기록.
 * 키 자체의 중복은 UNIQUE가 마지막으로 막고, 정상 흐름에서는 주문 행 락으로 직렬화해 UNIQUE 위반 없이 걸러낸다.
 */
@Entity
@Table(name = "idempotency_key", uniqueConstraints = {
        @UniqueConstraint(name = "uk_idempotency_key", columnNames = {"idempotency_key"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    public static final int KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = KEY_MAX_LENGTH)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    /** FAILED일 때 첫 요청이 받은 {@code ErrorCode}의 이름. 재요청에 같은 실패를 돌려주는 데 쓴다. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private IdempotencyKey(String idempotencyKey, Long orderId, IdempotencyStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.status = status;
    }

    public static IdempotencyKey create(String idempotencyKey, Long orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키는 필수입니다.");
        }
        if (idempotencyKey.length() > KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("멱등키는 " + KEY_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        return IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .orderId(orderId)
                .status(IdempotencyStatus.IN_PROGRESS)
                .build();
    }

    public void succeed() {
        assertInProgress();
        this.status = IdempotencyStatus.SUCCEEDED;
    }

    public void fail(String failureCode) {
        assertInProgress();
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("실패 코드는 필수입니다.");
        }
        this.status = IdempotencyStatus.FAILED;
        this.failureCode = failureCode;
    }

    public boolean isOwnedBy(Long orderId) {
        return this.orderId.equals(orderId);
    }

    private void assertInProgress() {
        if (status != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException("이미 결과가 확정된 멱등키입니다. 현재 상태: " + status);
        }
    }
}
