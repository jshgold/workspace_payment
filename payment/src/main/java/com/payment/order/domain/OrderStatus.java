package com.payment.order.domain;

public enum OrderStatus {
    PENDING,
    /** PG 승인 호출을 위해 한 요청이 선점한 상태. 서버가 이 상태에서 죽으면 복구(대사)가 필요하다. */
    PAYING,
    PAID,
    CANCELED
}
