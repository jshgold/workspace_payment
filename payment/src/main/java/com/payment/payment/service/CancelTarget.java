package com.payment.payment.service;

/** 취소 선점에 성공한 주문의 PG 취소 요청에 필요한 값. paymentKey는 PG가 결제를 식별하는 값이다. */
public record CancelTarget(Long orderId, String paymentKey) {
}
