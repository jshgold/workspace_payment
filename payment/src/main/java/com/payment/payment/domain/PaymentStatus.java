package com.payment.payment.domain;

/**
 * 결제 시도의 상태. 허용되는 전이는 {@code .harness/intent.md}의 Payment 전이표를 따른다.
 */
public enum PaymentStatus {

    /** PG 승인 요청 중 */
    REQUESTED,

    /** 승인 결과 모름. 대사로 확정한다. */
    UNKNOWN,

    APPROVED,

    /** 실패 확정. {@code failure_code}에 사유({@code ErrorCode} 이름)를 남긴다. */
    FAILED,

    CANCELED
}
