package com.payment.payment.domain;

/**
 * 취소 시도의 상태. 허용되는 전이는 {@code .harness/intent.md}의 PaymentCancel 전이표를 따른다.
 */
public enum PaymentCancelStatus {

    /** PG 취소 요청 중 */
    REQUESTED,

    /** 취소 결과 모름. 대사로 확정한다. (4-4) */
    UNKNOWN,

    /** 취소 완료. PG의 "이미 취소된 결제" 응답도 여기에 속한다. */
    COMPLETED,

    /** 취소 실패 확정. 결제는 살아 있다. {@code failure_code}에 사유를 남긴다. */
    FAILED
}
