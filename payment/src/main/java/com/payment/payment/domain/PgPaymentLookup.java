package com.payment.payment.domain;

/**
 * PG 결제 조회 결과. 대사가 승인 여부와 금액을 서버 기록과 비교하는 데 쓴다. (phase-04 4-4)
 *
 * @param status      PG가 알고 있는 결제 상태
 * @param totalAmount PG가 알고 있는 결제 금액. 대사는 승인 여부뿐 아니라 이 금액도 서버 금액과 비교한다.
 */
public record PgPaymentLookup(PgPaymentStatus status, long totalAmount) {

    public PgPaymentLookup {
        if (status == null) {
            throw new IllegalArgumentException("PG 결제 상태는 필수입니다.");
        }
    }
}
