package com.payment.order.domain;

/**
 * 주문 상태. 허용되는 전이는 {@code .harness/intent.md}의 상태 전이표를 따른다. 전이는 {@link Order}의 도메인 메서드에서만 일어난다.
 */
public enum OrderStatus {

    /** 결제 대기. 결제를 아직 시작하지 않음 */
    PENDING,

    /** 결제 진행 중. PG 승인 호출이 나가 있다. 서버가 이 상태에서 죽으면 오래된 PAYING을 대사로 확정한다. */
    PAYING,

    /** 결제 결과 모름. 타임아웃·5xx·보상 취소 실패로 응답을 놓쳐 대사를 기다린다. 재고는 쥐고 있다. */
    PAYMENT_UNKNOWN,

    PAID,

    /** 결제 실패로 종료. 돈이 나가지 않았음이 확정됐다. 다시 결제하려면 새 주문을 만든다. (최종 상태) */
    FAILED,

    /** 결제를 시작하지 않은 채 만료됨. (최종 상태, 4-4) */
    EXPIRED,

    /** PG 취소 호출이 나가 있다. 서버가 이 상태에서 죽으면 대사가 필요하다. */
    CANCELING,

    /** 취소 결과 모름. (4-3) */
    CANCEL_UNKNOWN,

    CANCELED;

    /** 결제가 진행 중이거나 이미 끝나서, 같은 주문의 새 결제 요청에 "처리 중"으로 답해야 하는 상태 */
    public boolean isPaymentInProgressOrDone() {
        return this == PAYING || this == PAYMENT_UNKNOWN || this == PAID;
    }
}
