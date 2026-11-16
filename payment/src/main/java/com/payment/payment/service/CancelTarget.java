package com.payment.payment.service;

/** 취소 선점에 성공한 주문의 PG 취소 요청에 필요한 값. */
public record CancelTarget(Long orderId, String pgTransactionId) {
}
