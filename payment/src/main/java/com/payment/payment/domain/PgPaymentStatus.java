package com.payment.payment.domain;

/**
 * PG 결제 조회 응답의 결제 상태. 토스페이먼츠 Payment 객체의 {@code status} 값과 이름이 같다. (phase-04 4-4)
 * 대사는 이 값으로 주문의 결과를 확정한다. 분류 기준은 {@code intent.md} 8절 "승인 대사 분류"를 따른다.
 */
public enum PgPaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED
}
