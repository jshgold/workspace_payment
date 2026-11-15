package com.payment.payment.service;

/** 결제 선점에 성공한 주문의 PG 요청에 필요한 값. amount는 서버가 주문에서 계산해 둔 금액이다. */
public record PaymentTarget(Long orderId, long amount) {
}
