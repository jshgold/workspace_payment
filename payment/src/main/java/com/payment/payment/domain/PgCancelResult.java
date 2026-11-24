package com.payment.payment.domain;

/**
 * PG 취소 성공 결과.
 *
 * @param alreadyCanceled PG가 "이미 취소된 결제"라고 답했는지. 전액 취소뿐이므로 성공과 같지만,
 *                        첫 시도에서 이 응답이 오면 우리가 모르는 경로로 취소된 것이라 호출하는 쪽이 경고를 남긴다.
 */
public record PgCancelResult(boolean alreadyCanceled) {

    public static PgCancelResult canceled() {
        return new PgCancelResult(false);
    }

    public static PgCancelResult wasAlreadyCanceled() {
        return new PgCancelResult(true);
    }
}
