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
 * 결제 시도 기록. 승인 결과가 아니라 "무엇을 시도했고 어떻게 끝났는가"를 남긴다. (phase-04 4-2+)
 * <ul>
 *   <li>PG를 호출하기 <b>전에</b> {@code REQUESTED}로 생성한다. 그래야 타임아웃처럼 결과를 모르는 경우에도
 *       어떤 {@code paymentKey}로 얼마를 시도했는지가 남는다.</li>
 *   <li>PG를 부르기 전에 거절한 시도(재고 부족, 요청 금액 불일치)는 처음부터 {@code FAILED}로 생성한다.</li>
 *   <li>Order와 1:1이다. 재시도는 새 주문으로 하므로 한 주문에 결제 시도는 하나뿐이다. ({@code order_id} UNIQUE)</li>
 *   <li>멱등키도 이 행에 둔다. 키 선점과 시도 기록이 같은 INSERT라 한 트랜잭션으로 묶인다. ({@code idempotency_key} UNIQUE)</li>
 * </ul>
 */
@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_id", columnNames = {"order_id"}),
        @UniqueConstraint(name = "uk_payment_payment_key", columnNames = {"payment_key"}),
        @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = {"idempotency_key"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    /** 토스 paymentKey 최대 길이(200자)에 맞춘다. PG가 식별자 길이를 늘릴 수 있어 여유를 두지 않는다. */
    public static final int PAYMENT_KEY_MAX_LENGTH = 200;
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 결제창 인증 후 PG가 발급한 결제 식별자. 승인·취소·조회에 쓴다. 클라이언트가 승인 요청에 담아 보낸다. */
    @Column(name = "payment_key", nullable = false, length = PAYMENT_KEY_MAX_LENGTH)
    private String paymentKey;

    /** 클라이언트가 보낸 멱등키. 같은 키의 재요청에 이 행의 결과를 돌려준다. */
    @Column(name = "idempotency_key", nullable = false, length = IDEMPOTENCY_KEY_MAX_LENGTH)
    private String idempotencyKey;

    /** 서버가 주문에서 계산한 요청 금액. 클라이언트 값이 아니다. */
    @Column(nullable = false)
    private long amount;

    /** PG가 실제로 승인한 금액. 승인 응답을 받았을 때만 채운다. (금액 불일치·보상 실패 건의 사후 확인용) */
    @Column(name = "approved_amount")
    private Long approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** 실패·결과 모름의 사유. {@link ErrorCode} 이름을 저장한다. 같은 키의 재요청에 같은 에러를 돌려줄 때 쓴다. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Payment(Long orderId, String paymentKey, String idempotencyKey, long amount,
                    PaymentStatus status, String failureCode) {
        this.orderId = orderId;
        this.paymentKey = paymentKey;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = status;
        this.failureCode = failureCode;
    }

    /** PG 승인을 호출하기 직전의 시도 기록. */
    public static Payment request(Long orderId, String idempotencyKey, String paymentKey, long amount) {
        validate(orderId, idempotencyKey, paymentKey, amount);
        return Payment.builder()
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .paymentKey(paymentKey)
                .amount(amount)
                .status(PaymentStatus.REQUESTED)
                .build();
    }

    /** PG를 부르기 전에 거절한 시도 기록. (재고 부족, 요청 금액 불일치) */
    public static Payment rejected(Long orderId, String idempotencyKey, String paymentKey, long amount,
                                   ErrorCode failureCode) {
        validate(orderId, idempotencyKey, paymentKey, amount);
        if (failureCode == null) {
            throw new IllegalArgumentException("실패 코드는 필수입니다.");
        }
        return Payment.builder()
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .paymentKey(paymentKey)
                .amount(amount)
                .status(PaymentStatus.FAILED)
                .failureCode(failureCode.name())
                .build();
    }

    public void approve(long approvedAmount) {
        assertStatus(PaymentStatus.REQUESTED, "승인 처리할 수 없는 결제 상태입니다.");
        this.status = PaymentStatus.APPROVED;
        this.approvedAmount = approvedAmount;
    }

    /**
     * 돈이 나가지 않았음이 확정된 실패. PG가 승인했다가 보상 취소한 경우에는 승인 금액도 남긴다.
     *
     * @param approvedAmount PG 승인 응답을 받았으면 그 금액, 받지 못했으면 null
     */
    public void fail(ErrorCode failureCode, Long approvedAmount) {
        assertStatus(PaymentStatus.REQUESTED, "실패 처리할 수 없는 결제 상태입니다.");
        if (failureCode == null) {
            throw new IllegalArgumentException("실패 코드는 필수입니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode.name();
        this.approvedAmount = approvedAmount;
    }

    /**
     * 승인 여부를 모른다. 대사로 확정한다.
     *
     * @param reason         사유가 따로 있으면 그 코드(보상 취소 실패 등), 단순 타임아웃이면 null
     * @param approvedAmount PG 승인 응답을 받았으면 그 금액, 받지 못했으면 null
     */
    public void markUnknown(ErrorCode reason, Long approvedAmount) {
        assertStatus(PaymentStatus.REQUESTED, "결과 모름으로 바꿀 수 없는 결제 상태입니다.");
        this.status = PaymentStatus.UNKNOWN;
        this.failureCode = reason == null ? null : reason.name();
        this.approvedAmount = approvedAmount;
    }

    /** 승인된 결제의 취소가 확정됐다. */
    public void cancel() {
        assertStatus(PaymentStatus.APPROVED, "취소 처리할 수 없는 결제 상태입니다.");
        this.status = PaymentStatus.CANCELED;
    }

    /** 승인 대사의 대상인 시도인지. (요청 중에 서버가 죽었거나, 결과 모름) */
    public boolean isAwaitingReconciliation() {
        return this.status == PaymentStatus.REQUESTED || this.status == PaymentStatus.UNKNOWN;
    }

    /** 보상 취소가 실패해 결과 모름으로 남은 시도인지 */
    public boolean isCompensationFailed() {
        return this.status == PaymentStatus.UNKNOWN
                && ErrorCode.PAYMENT_CANCEL_FAILED.name().equals(this.failureCode);
    }

    /**
     * 대사: PG 조회로 승인이 확인됐다. {@code REQUESTED | UNKNOWN → APPROVED} (4-4)
     * 보상 취소 실패 사유는 결과가 확정됐으므로 지운다. (경위는 대사 로그에 남는다)
     */
    public void confirmApprovedByReconciliation(long approvedAmount) {
        assertAwaitingReconciliation();
        this.status = PaymentStatus.APPROVED;
        this.approvedAmount = approvedAmount;
        this.failureCode = null;
    }

    /**
     * 대사: 돈이 나가지 않았거나 돌려줬음이 확인됐다. {@code REQUESTED | UNKNOWN → FAILED} (4-4)
     *
     * @param approvedAmount PG가 승인했다가 취소된 금액을 알면 그 금액, 아니면 기존 값을 유지하도록 null
     */
    public void confirmFailedByReconciliation(ErrorCode failureCode, Long approvedAmount) {
        assertAwaitingReconciliation();
        if (failureCode == null) {
            throw new IllegalArgumentException("실패 코드는 필수입니다.");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode.name();
        if (approvedAmount != null) {
            this.approvedAmount = approvedAmount;
        }
    }

    /** 대사: 금액이 다른 승인을 다시 취소하려다 실패했다. {@code REQUESTED | UNKNOWN → UNKNOWN} + {@code PAYMENT_CANCEL_FAILED} */
    public void keepUnknownAfterCancelFailure(long approvedAmount) {
        assertAwaitingReconciliation();
        this.status = PaymentStatus.UNKNOWN;
        this.failureCode = ErrorCode.PAYMENT_CANCEL_FAILED.name();
        this.approvedAmount = approvedAmount;
    }

    private void assertAwaitingReconciliation() {
        if (!isAwaitingReconciliation()) {
            throw new IllegalStateException("대사로 확정할 수 없는 결제 상태입니다. 현재 상태: " + this.status);
        }
    }

    public boolean belongsTo(Long orderId) {
        return this.orderId.equals(orderId);
    }

    private static void validate(Long orderId, String idempotencyKey, String paymentKey, long amount) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("멱등키는 필수입니다.");
        }
        if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("멱등키는 " + IDEMPOTENCY_KEY_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new IllegalArgumentException("결제 키는 필수입니다.");
        }
        if (paymentKey.length() > PAYMENT_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("결제 키는 " + PAYMENT_KEY_MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
    }

    private void assertStatus(PaymentStatus expected, String message) {
        if (this.status != expected) {
            throw new IllegalStateException(message + " 현재 상태: " + this.status);
        }
    }
}
