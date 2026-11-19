package com.payment.payment.service;

/**
 * 결제 시작 요청.
 * <ul>
 *   <li>idempotencyKey: 클라이언트가 보낸 멱등키. 같은 시도의 재전송이면 같은 값이다.</li>
 *   <li>requestedAmount: 클라이언트가 결제창에서 본 금액. 서버 계산 금액과 비교하는 용도로만 쓴다.</li>
 *   <li>paymentKey: 결제창 인증 후 PG가 발급한 결제 식별자.</li>
 * </ul>
 */
public record StartPayingCommand(Long orderId, String idempotencyKey, long requestedAmount, String paymentKey) {
}
