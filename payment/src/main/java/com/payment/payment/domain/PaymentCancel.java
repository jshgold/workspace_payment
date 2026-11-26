package com.payment.payment.domain;

import java.time.LocalDateTime;

import com.payment.common.exception.ErrorCode;

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
 * 사용자 결제 취소의 시도 기록. (phase-04 4-3)
 * <ul>
 *   <li>PG 취소를 호출하기 <b>전에</b> {@code REQUESTED}로 생성한다. 응답을 놓쳐도 "취소를 시도했다"는 사실이 남는다.</li>
 *   <li>Order와 1:N이다. PG가 거절하면 주문이 PAID로 돌아가 새 키로 다시 취소할 수 있다.</li>
 *   <li>멱등키를 이 행에 둔다. 같은 키 + 같은 내용이면 이 행의 결과를 돌려주고, 내용이 다르면 거절한다.</li>
 *   <li>보상 취소(승인 후 반영 실패)는 기록하지 않는다. 그 결과는 {@link Payment}에 남는다.</li>
 * </ul>
 */
@Entity
@Table(name = "payment_cancel", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_cancel_idempotency_key", columnNames = {"idempotency_key"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancel {

    /** 토스 {@code cancelReason} 최대 길이 */
    public static final int REASON_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "idempotency_key", nullable = false, length = Payment.IDEMPOTENCY_KEY_MAX_LENGTH)
    private String idempotencyKey;

    /** PG에 보낸 취소 사유. 같은 키 재요청이 같은 내용인지 비교하는 데도 쓴다. */
    @Column(nullable = false, length = REASON_MAX_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentCancelStatus status;

    /** 실패 사유. {@link ErrorCode} 이름을 저장한다. 같은 키의 재요청에 같은 에러를 돌려줄 때 쓴다. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentCancel(Long orderId, String idempotencyKey, String reason, PaymentCancelStatus status) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.status = status;
    }

    /** PG 취소를 호출하기 직전의 시도 기록. */
    public static PaymentCancel request(Long orderId, String idempotencyKey, String reason) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키는 필수입니다.");
        }
        if (idempotencyKey.length() > Payment.IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("멱등키는 " + Payment.IDEMPOTENCY_KEY_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("취소 사유는 필수입니다.");
        }
        if (reason.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("취소 사유는 " + REASON_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return PaymentCancel.builder()
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .reason(reason)
                .status(PaymentCancelStatus.REQUESTED)
                .build();
    }

    /** PG에서 취소가 확정됐다. (이미 취소된 결제 응답 포함) */
    public void complete() {
        assertStatus(PaymentCancelStatus.REQUESTED, "취소 완료 처리할 수 없는 취소 시도 상태입니다.");
        this.status = PaymentCancelStatus.COMPLETED;
    }

    /** 취소가 일어나지 않았음이 확정됐다. (PG 거절, 요청이 PG에 닿지 않음) */
    public void fail(ErrorCode failureCode) {
        assertStatus(PaymentCancelStatus.REQUESTED, "취소 실패 처리할 수 없는 취소 시도 상태입니다.");
        if (failureCode == null) {
            throw new IllegalArgumentException("실패 코드는 필수입니다.");
        }
        this.status = PaymentCancelStatus.FAILED;
        this.failureCode = failureCode.name();
    }

    /** PG 응답을 놓쳐 취소 여부를 모른다. 대사로 확정한다. */
    public void markUnknown() {
        assertStatus(PaymentCancelStatus.REQUESTED, "결과 모름으로 바꿀 수 없는 취소 시도 상태입니다.");
        this.status = PaymentCancelStatus.UNKNOWN;
    }

    /** 대사로 확정할 시도인지. (요청 중에 서버가 죽었거나, 결과 모름) */
    public boolean isAwaitingReconciliation() {
        return this.status == PaymentCancelStatus.REQUESTED || this.status == PaymentCancelStatus.UNKNOWN;
    }

    /** 대사: PG 조회로 취소가 확인됐다. {@code REQUESTED | UNKNOWN → COMPLETED} (4-4) */
    public void confirmCompletedByReconciliation() {
        assertAwaitingReconciliation();
        this.status = PaymentCancelStatus.COMPLETED;
    }

    /** 대사: PG 조회로 취소되지 않았음이 확인됐다. {@code REQUESTED | UNKNOWN → FAILED} (4-4) */
    public void confirmNotAppliedByReconciliation() {
        assertAwaitingReconciliation();
        this.status = PaymentCancelStatus.FAILED;
        this.failureCode = ErrorCode.PAYMENT_CANCEL_NOT_APPLIED.name();
    }

    /** 대사: PG 조회가 NOT_FOUND_PAYMENT라 사람이 확인한다. 결과를 모르는 것은 그대로이므로 {@code REQUESTED → UNKNOWN}, UNKNOWN이면 그대로. (4-4) */
    public void keepUnknownForReview() {
        assertAwaitingReconciliation();
        this.status = PaymentCancelStatus.UNKNOWN;
    }

    private void assertAwaitingReconciliation() {
        if (!isAwaitingReconciliation()) {
            throw new IllegalStateException("대사로 확정할 수 없는 취소 시도 상태입니다. 현재 상태: " + this.status);
        }
    }

    /** 같은 키의 재요청이 처음 요청과 같은 내용(주문, 사유)인지 본다. */
    public boolean isSameRequest(Long orderId, String reason) {
        return this.orderId.equals(orderId) && this.reason.equals(reason);
    }

    private void assertStatus(PaymentCancelStatus expected, String message) {
        if (this.status != expected) {
            throw new IllegalStateException(message + " 현재 상태: " + this.status);
        }
    }
}
