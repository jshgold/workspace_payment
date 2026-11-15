package com.payment.payment.domain;

public enum IdempotencyStatus {
    /** 첫 요청이 처리 중이거나, PG 타임아웃처럼 결과를 모르는 상태. 같은 키의 재요청은 "처리 중"을 받는다. */
    IN_PROGRESS,
    /** 결제가 완료됐다. 같은 키의 재요청에는 첫 성공 결과를 돌려준다. */
    SUCCEEDED,
    /** 돈이 나가지 않은 것이 확정된 실패. 같은 키의 재요청에는 같은 실패를 돌려준다. */
    FAILED
}
