package com.payment.payment.domain;

/** PG 승인 요청. amount는 클라이언트 값이 아니라 서버가 Order에서 계산한 금액이어야 한다. */
public record PgApproveRequest(Long orderId, long amount) {
}
