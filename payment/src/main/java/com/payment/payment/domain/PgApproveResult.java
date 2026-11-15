package com.payment.payment.domain;

/** PG 승인 결과. approvedAmount는 PG가 실제로 승인한 금액이며, 서버 계산 금액과 반드시 비교해야 한다. (3-3) */
public record PgApproveResult(String pgTransactionId, long approvedAmount) {
}
