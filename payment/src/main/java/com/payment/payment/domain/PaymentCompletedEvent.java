package com.payment.payment.domain;

/**
 * 결제가 완료(Order PAID, Payment 저장)됐다는 도메인 이벤트.
 * 결제 트랜잭션 안에서 발행되지만, 후처리는 그 트랜잭션이 커밋된 뒤에만 실행된다. (3-6)
 */
public record PaymentCompletedEvent(Long orderId, Long paymentId, String pgTransactionId, long amount) {
}
