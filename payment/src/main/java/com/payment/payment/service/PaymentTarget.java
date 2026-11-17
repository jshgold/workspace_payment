package com.payment.payment.service;

/**
 * 결제 선점에 성공한 주문의 PG 요청에 필요한 값.
 * amount는 서버가 주문에서 계산해 둔 금액이고, orderNo는 PG에 보내는 주문 식별자다. (내부 PK는 PG로 내보내지 않는다)
 */
public record PaymentTarget(Long orderId, String orderNo, long amount) {
}
