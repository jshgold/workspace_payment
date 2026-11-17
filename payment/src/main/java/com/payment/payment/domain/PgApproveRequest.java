package com.payment.payment.domain;

/**
 * PG 승인 요청.
 * <ul>
 *   <li>orderNo: PG에 보내는 주문 식별자. 내부 PK가 아니라 외부 노출용 주문번호다.</li>
 *   <li>paymentKey: 결제창에서 구매자가 인증을 마친 뒤 PG가 발급한 결제 식별자. 클라이언트가 보내준다.</li>
 *   <li>amount: 클라이언트 값이 아니라 서버가 Order에서 계산한 금액이어야 한다.</li>
 * </ul>
 */
public record PgApproveRequest(String orderNo, String paymentKey, long amount) {

    public PgApproveRequest {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("주문번호는 필수입니다.");
        }
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new IllegalArgumentException("결제 키는 필수입니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("승인 금액은 0보다 커야 합니다.");
        }
    }
}
